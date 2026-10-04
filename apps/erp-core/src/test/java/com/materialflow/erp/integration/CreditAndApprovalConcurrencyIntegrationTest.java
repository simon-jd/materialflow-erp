package com.materialflow.erp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.materialflow.approval.ApprovalDecision;
import com.materialflow.erp.approval.ApprovalApplicationService;
import com.materialflow.erp.approval.ApprovalDecisionRequest;
import com.materialflow.erp.common.BusinessException;
import com.materialflow.erp.order.CreateOrderRequest;
import com.materialflow.erp.order.OrderApplicationService;
import com.materialflow.identity.ActorContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
class CreditAndApprovalConcurrencyIntegrationTest {
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CEMENT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PLYWOOD = UUID.fromString("55555555-5555-5555-5555-555555555556");
    private static final UUID WAREHOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CEMENT_LOCATION = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID PLYWOOD_LOCATION = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    private static final UUID SALES_USER = UUID.fromString("66666666-6666-6666-6666-666666666601");
    private static final UUID APPROVER_USER = UUID.fromString("66666666-6666-6666-6666-666666666602");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("materialflow").withUsername("materialflow").withPassword("materialflow");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("materialflow.security.enabled", () -> "false");
    }

    @Autowired OrderApplicationService orders;
    @Autowired ApprovalApplicationService approvals;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentApprovalAndCreditReservationAllowOnlyLegalWinners() throws Exception {
        ActorContext sales = new ActorContext(SALES_USER, ORGANIZATION,
                Set.of("SALES_ORDER_WRITE"), Set.of(WAREHOUSE));
        ActorContext approver = new ActorContext(APPROVER_USER, ORGANIZATION,
                Set.of("APPROVAL_DECIDE"), Set.of(WAREHOUSE));
        var pending = orders.create(new CreateOrderRequest(CUSTOMER, false, List.of(
                new CreateOrderRequest.Line(PLYWOOD, WAREHOUSE, PLYWOOD_LOCATION, new BigDecimal("5"),
                        new BigDecimal("50"), BigDecimal.ZERO, "Synthetic manual-price test"))),
                "approval-race-order", sales);
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");

        AtomicInteger approvalSuccess = new AtomicInteger();
        AtomicInteger approvalRejected = new AtomicInteger();
        race(2, candidate -> {
            try {
                approvals.decide(pending.approvalInstanceId(),
                        new ApprovalDecisionRequest(ApprovalDecision.APPROVE, "Concurrent checker test"),
                        "approval-race-" + candidate, approver);
                approvalSuccess.incrementAndGet();
            } catch (BusinessException exception) {
                assertThat(exception.errorCode()).isIn("APPROVAL_TERMINAL", "APPROVAL_CONCURRENT_UPDATE");
                approvalRejected.incrementAndGet();
            }
        });
        assertThat(approvalSuccess).hasValue(1);
        assertThat(approvalRejected).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions WHERE approval_instance_id = ?",
                Long.class, pending.approvalInstanceId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT credit_override_approved FROM sales_orders WHERE id = ?",
                Boolean.class, pending.id())).isFalse();

        jdbc.update("UPDATE credit_accounts SET credit_limit = 5000 WHERE customer_id = ?", CUSTOMER);
        jdbc.update("UPDATE approval_templates SET amount_threshold = 999999 WHERE object_type = 'SALES_ORDER'");
        AtomicInteger creditReserved = new AtomicInteger();
        AtomicInteger creditApprovalRequired = new AtomicInteger();
        race(10, candidate -> {
            var response = orders.create(new CreateOrderRequest(CUSTOMER, true, List.of(
                    new CreateOrderRequest.Line(CEMENT, WAREHOUSE, CEMENT_LOCATION, new BigDecimal("100"),
                            null, null, null))), "credit-race-" + candidate, sales);
            if ("RESERVED".equals(response.status())) {
                creditReserved.incrementAndGet();
            } else {
                assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
                creditApprovalRequired.incrementAndGet();
            }
        });

        assertThat(creditReserved).hasValue(4);
        assertThat(creditApprovalRequired).hasValue(6);
        var credit = jdbc.queryForMap("SELECT credit_limit, credit_reserved, credit_used FROM credit_accounts WHERE customer_id = ?",
                CUSTOMER);
        BigDecimal committed = ((BigDecimal) credit.get("credit_reserved")).add((BigDecimal) credit.get("credit_used"));
        assertThat(committed).isLessThanOrEqualTo((BigDecimal) credit.get("credit_limit"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balances WHERE reserved > on_hand",
                Long.class)).isZero();
    }

    private void race(int contenders, ThrowingIntConsumer task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < contenders; index++) {
                int candidate = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    task.accept(candidate);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int value) throws Exception;
    }
}
