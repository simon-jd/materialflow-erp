package com.materialflow.erp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.fulfillment.CreateShipmentRequest;
import com.materialflow.erp.fulfillment.FulfillmentApplicationService;
import com.materialflow.erp.order.CreateOrderRequest;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.erp.reservation.ReservationReleaseApplicationService;
import com.materialflow.identity.ActorContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
class ReservationExpiryShipmentRaceIntegrationTest {
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MATERIAL = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID WAREHOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOCATION = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID SALES = UUID.fromString("66666666-6666-6666-6666-666666666601");
    private static final UUID WAREHOUSE_USER = UUID.fromString("66666666-6666-6666-6666-666666666603");
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("materialflow").withUsername("materialflow").withPassword("materialflow");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("materialflow.security.enabled", () -> "false");
        registry.add("materialflow.reservation.expiry-enabled", () -> "false");
    }

    @Autowired OrderApplicationService orders;
    @Autowired FulfillmentApplicationService fulfillment;
    @Autowired ReservationReleaseApplicationService releases;
    @Autowired JdbcTemplate jdbc;

    @Test
    void shipmentAndExpiryRaceConvergesToExactlyOneLegalOutcome() throws Exception {
        ActorContext sales = new ActorContext(SALES, ORGANIZATION, Set.of("SALES_ORDER_WRITE"), Set.of(WAREHOUSE));
        ActorContext warehouse = new ActorContext(WAREHOUSE_USER, ORGANIZATION, Set.of("WAREHOUSE_OPERATE"),
                Set.of(WAREHOUSE));
        var order = orders.create(new CreateOrderRequest(CUSTOMER, true,
                List.of(new CreateOrderRequest.Line(MATERIAL, WAREHOUSE, LOCATION, new BigDecimal("10"),
                        null, null, null))), "expiry-race-order", sales);
        UUID reservation = jdbc.queryForObject("SELECT id FROM inventory_reservations WHERE order_id = ?",
                UUID.class, order.id());
        jdbc.update("UPDATE inventory_reservations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                reservation);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> shipmentResult = new AtomicReference<>();
        AtomicReference<String> expiryResult = new AtomicReference<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ship = executor.submit(() -> {
                start.await();
                try {
                    fulfillment.ship(order.id(), new CreateShipmentRequest(List.of(
                            new CreateShipmentRequest.Line(reservation, new BigDecimal("10")))),
                            "expiry-race-ship", warehouse);
                    shipmentResult.set("SHIPPED");
                } catch (BusinessException exception) {
                    shipmentResult.set(exception.errorCode());
                }
                return null;
            });
            var expire = executor.submit(() -> {
                start.await();
                expiryResult.set(releases.expire(order.id(), ORGANIZATION, SYSTEM) ? "EXPIRED" : "LOST_RACE");
                return null;
            });
            start.countDown();
            ship.get();
            expire.get();
        }

        String status = jdbc.queryForObject("SELECT status FROM sales_orders WHERE id = ?", String.class, order.id());
        assertThat(status).isIn("FULFILLED", "EXPIRED");
        if ("FULFILLED".equals(status)) {
            assertThat(shipmentResult).hasValue("SHIPPED");
            assertThat(expiryResult).hasValue("LOST_RACE");
        } else {
            assertThat(expiryResult).hasValue("EXPIRED");
            assertThat(shipmentResult).hasValue("ORDER_NOT_SHIPPABLE");
        }
        var inventory = jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_balances WHERE material_id = ?",
                MATERIAL);
        assertThat((BigDecimal) inventory.get("reserved")).isZero();
        assertThat((BigDecimal) inventory.get("on_hand")).isIn(new BigDecimal("990.000000"),
                new BigDecimal("1000.000000"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances WHERE reserved > on_hand",
                Long.class)).isZero();
    }
}
