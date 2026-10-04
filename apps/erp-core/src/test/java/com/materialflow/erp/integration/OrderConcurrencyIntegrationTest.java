package com.materialflow.erp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.materialflow.erp.order.CreateOrderRequest;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.identity.ActorContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
        ActorContext actor = new ActorContext(SALES_ACTOR, ORGANIZATION, Set.of("SALES_ORDER_WRITE"), Set.of(WAREHOUSE));
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(21L);
    }

    private CreateOrderRequest order(BigDecimal quantity) {
        return new CreateOrderRequest(CUSTOMER, true,
                List.of(new CreateOrderRequest.Line(MATERIAL, WAREHOUSE, LOCATION, quantity,
                        null, null, null)));
    }
}
