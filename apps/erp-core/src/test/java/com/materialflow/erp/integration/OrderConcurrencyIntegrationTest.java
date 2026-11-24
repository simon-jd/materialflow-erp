package com.materialflow.erp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.materialflow.erp.order.CreateOrderRequest;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.erp.order.OrderResponse;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.identity.ActorContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class OrderConcurrencyIntegrationTest {
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MATERIAL = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID WAREHOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOCATION = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID SALES_ACTOR = UUID.fromString("66666666-6666-6666-6666-666666666601");
    private static final ActorContext SALES =
            new ActorContext(SALES_ACTOR, ORGANIZATION, Set.of("SALES_ORDER_WRITE"), Set.of(WAREHOUSE));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("materialflow")
            .withUsername("materialflow")
            .withPassword("materialflow");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("materialflow.security.enabled", () -> "false");
    }

    @Autowired
    OrderApplicationService orders;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsIdempotencyAndConcurrentHotSkuReservationsPreserveInvariants() throws Exception {
        ActorContext actor = SALES;
        var initialRequest = order(new BigDecimal("10"));
        var first = orders.create(initialRequest, "initial-order", actor);
        var replay = orders.create(initialRequest, "initial-order", actor);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> orders.create(order(new BigDecimal("11")), "initial-order", actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_orders WHERE id = ?", Long.class, first.id()))
                .isEqualTo(1L);

        int contenders = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < contenders; index++) {
                int candidate = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    orders.create(order(new BigDecimal("100")), "hot-sku-" + candidate, actor);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        var balance = jdbc.queryForMap("""
                SELECT on_hand, reserved, available FROM inventory_balances
                 WHERE organization_id = ? AND warehouse_id = ? AND material_id = ? AND location_id = ?
                """, ORGANIZATION, WAREHOUSE, MATERIAL, LOCATION);
        assertThat((BigDecimal) balance.get("on_hand")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) balance.get("reserved")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) balance.get("available")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_balances WHERE reserved > on_hand OR available < 0
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                 WHERE aggregate_id IN (SELECT order_id FROM sales_order_lines WHERE material_id = ?)
                """, Long.class, MATERIAL)).isEqualTo(21L);
    }

    /**
     * Orders from one customer serialize on that customer's credit row before they reach inventory, so
     * the test above never contends on the inventory row. Distinct customers do: here 48 orders from 12
     * customers race for one balance, and 8 more submissions reuse in-flight idempotency keys.
     */
    @Test
    void hotSkuOrdersFromManyCustomersNeverOversellAndConcurrentKeyReuseCreatesOneOrder() throws Exception {
        HotSku sku = hotSku("HOT-CI-01", new BigDecimal("250"));
        List<UUID> customers = customersWithCredit("HOT-CI", 12);
        int distinctOrders = 48;
        int duplicateSubmissions = 8;
        List<String> keys = new ArrayList<>();
        List<CreateOrderRequest> requests = new ArrayList<>();
        for (int index = 0; index < distinctOrders + duplicateSubmissions; index++) {
            int order = index % distinctOrders;
            keys.add("hot-ci-" + order);
            requests.add(new CreateOrderRequest(customers.get(order % customers.size()), true,
                    List.of(sku.line(new BigDecimal("7")))));
        }

        List<OrderResponse> responses = submitConcurrently(keys, requests);

        Map<String, Set<UUID>> orderIdsByKey = new HashMap<>();
        for (int index = 0; index < responses.size(); index++) {
            orderIdsByKey.computeIfAbsent(keys.get(index), key -> new HashSet<>()).add(responses.get(index).id());
        }
        assertThat(orderIdsByKey).hasSize(distinctOrders).allSatisfy((key, ids) -> assertThat(ids).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT order_id) FROM sales_order_lines WHERE material_id = ?",
                Long.class, sku.material())).isEqualTo(distinctOrders);

        // 250 units cover 35 full orders of 7 and 5 units of a 36th; with partial fulfillment the rest backorder.
        UUID balanceId = sku.balanceId();
        var balance = jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_balances WHERE id = ?", balanceId);
        assertThat((BigDecimal) balance.get("on_hand")).isEqualByComparingTo("250");
        assertThat((BigDecimal) balance.get("reserved")).isEqualByComparingTo("250");
        assertThat(sum("SELECT sum(allocated_quantity) FROM sales_order_lines WHERE material_id = ?", sku.material()))
                .isEqualByComparingTo("250");
        assertThat(sum("""
                SELECT sum(quantity) FROM inventory_reservations WHERE inventory_balance_id = ? AND status = 'ACTIVE'
                """, balanceId)).isEqualByComparingTo("250");
        assertThat(sum("""
                SELECT sum(quantity) FROM inventory_ledger WHERE inventory_balance_id = ? AND movement_type = 'RESERVE'
                """, balanceId)).isEqualByComparingTo("250");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_ledger WHERE inventory_balance_id = ? AND movement_type = 'RESERVE'
                """, Long.class, balanceId)).isEqualTo(36L);
        assertThat(statusCounts(sku.material())).containsOnly(
                entry("RESERVED", 35L), entry("PARTIALLY_ALLOCATED", 1L), entry("BACKORDERED", 12L));
        assertThat(sum("SELECT sum(shortage_quantity) FROM procurement_suggestions WHERE material_id = ?",
                sku.material())).isEqualByComparingTo("86");
        assertThat(sum("""
                SELECT sum(c.amount) FROM credit_ledger c
                 WHERE c.order_id IN (SELECT order_id FROM sales_order_lines WHERE material_id = ?)
                """, sku.material())).isEqualByComparingTo("3360.00");
    }

    private List<OrderResponse> submitConcurrently(List<String> keys, List<CreateOrderRequest> requests)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResponse>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                CreateOrderRequest request = requests.get(index);
                futures.add(executor.submit(() -> {
                    start.await();
                    return orders.create(request, key, SALES);
                }));
            }
            start.countDown();
            List<OrderResponse> responses = new ArrayList<>();
            for (Future<OrderResponse> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }

    private HotSku hotSku(String code, BigDecimal onHand) {
        UUID material = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID balance = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO materials (id, organization_id, sku, name, base_unit)
                VALUES (?, ?, ?, ?, 'PIECE')
                """, material, ORGANIZATION, code, "Synthetic contention SKU " + code);
        jdbc.update("""
                INSERT INTO price_rules
                    (id, organization_id, source, material_id, unit_price, discount_rate, rule_version, valid_from)
                VALUES (?, ?, 'STANDARD', ?, 10.0000, 0, 1, TIMESTAMPTZ '2026-01-01T00:00:00Z')
                """, UUID.randomUUID(), ORGANIZATION, material);
        jdbc.update("""
                INSERT INTO inventory_balances (id, organization_id, warehouse_id, material_id, location_id, on_hand)
                VALUES (?, ?, ?, ?, ?, ?)
                """, balance, ORGANIZATION, WAREHOUSE, material, location, onHand);
        return new HotSku(material, location, balance);
    }

    private List<UUID> customersWithCredit(String codePrefix, int count) {
        List<UUID> customers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID customer = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO customers (id, organization_id, code, name, region_code)
                    VALUES (?, ?, ?, ?, 'WEST')
                    """, customer, ORGANIZATION, codePrefix + "-" + index, "Synthetic contention customer " + index);
            jdbc.update("""
                    INSERT INTO credit_accounts (id, organization_id, customer_id, credit_limit)
                    VALUES (?, ?, ?, 100000.00)
                    """, UUID.randomUUID(), ORGANIZATION, customer);
            customers.add(customer);
        }
        return customers;
    }

    private BigDecimal sum(String sql, UUID id) {
        return jdbc.queryForObject(sql, BigDecimal.class, id);
    }

    private Map<String, Long> statusCounts(UUID material) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT o.status, count(*) FROM sales_orders o
                 WHERE o.id IN (SELECT order_id FROM sales_order_lines WHERE material_id = ?)
                 GROUP BY o.status
                """, row -> {
                    counts.put(row.getString(1), row.getLong(2));
                }, material);
        return counts;
    }

    private CreateOrderRequest order(BigDecimal quantity) {
        return new CreateOrderRequest(CUSTOMER, true,
                List.of(new CreateOrderRequest.Line(MATERIAL, WAREHOUSE, LOCATION, quantity,
                        null, null, null)));
    }

    private record HotSku(UUID material, UUID location, UUID balanceId) {
        CreateOrderRequest.Line line(BigDecimal quantity) {
            return new CreateOrderRequest.Line(material, WAREHOUSE, location, quantity, null, null, null);
        }
    }
}
