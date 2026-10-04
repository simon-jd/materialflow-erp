package com.materialflow.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.materialflow.approval.ApprovalDecision;
import com.materialflow.approval.ApprovalInstanceState;
import com.materialflow.approval.ApprovalMode;
import com.materialflow.contracts.events.EventEnvelope;
import com.materialflow.pricing.PriceRule;
import com.materialflow.pricing.PriceSource;
import com.materialflow.pricing.PricingRequest;
import com.materialflow.pricing.PricingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MaterialFlowBenchmark {
    @State(Scope.Thread)
    public static class BenchmarkState {
        private final UUID material = UUID.fromString("55555555-5555-5555-5555-555555555555");
        private final UUID customer = UUID.fromString("33333333-3333-3333-3333-333333333333");
        private final Instant now = Instant.parse("2026-08-31T20:00:00Z");
        private PricingService pricing;
        private PricingRequest request;
        private List<PriceRule> rules;
        private ObjectMapper objectMapper;
        private EventEnvelope<Map<String, Object>> event;
        private UUID applicant;
        private UUID firstApprover;
        private UUID secondApprover;
        private Set<UUID> approvers;

        @Setup(Level.Trial)
        public void setup() {
            pricing = new PricingService();
            request = new PricingRequest(material, customer, "WEST", new BigDecimal("125.750000"), now,
                    null, null, null, null);
            rules = List.of(
                    rule(PriceSource.STANDARD, null, null, "11.50", "0", 1),
                    rule(PriceSource.REGIONAL, null, "WEST", "11.10", "0.01", 2),
                    rule(PriceSource.CUSTOMER_SPECIAL, customer, null, "10.95", "0.02", 2),
                    rule(PriceSource.CONTRACT, customer, null, "10.75", "0.025", 3));
            objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
            event = new EventEnvelope<>(UUID.randomUUID(), "SalesOrderAllocated", UUID.randomUUID(), 7,
                    UUID.randomUUID(), now, 1, "0123456789abcdef0123456789abcdef", null,
                    UUID.randomUUID(), Map.of("status", "RESERVED", "lineCount", 12, "amount", "1317.42"));
            applicant = UUID.fromString("66666666-6666-6666-6666-666666666601");
            firstApprover = UUID.fromString("66666666-6666-6666-6666-666666666602");
            secondApprover = UUID.fromString("66666666-6666-6666-6666-666666666603");
            approvers = Set.of(firstApprover, secondApprover,
                    UUID.fromString("66666666-6666-6666-6666-666666666604"));
        }

        private PriceRule rule(
                PriceSource source,
                UUID customerId,
                String region,
                String unitPrice,
                String discount,
                long version) {
            return new PriceRule(UUID.randomUUID(), source, material, customerId, region,
                    new BigDecimal(unitPrice), new BigDecimal(discount), version, now.minusSeconds(86400), null);
        }
    }

    @Benchmark
    public Object selectAndSnapshotContractPrice(BenchmarkState state) {
        return state.pricing.quote(state.request, state.rules);
    }

    @Benchmark
    public byte[] serializeDomainEvent(BenchmarkState state) throws Exception {
        return state.objectMapper.writeValueAsBytes(state.event);
    }

    @Benchmark
    public Object evaluateTwoOfThreeApprovalRule(BenchmarkState state) {
        var approval = new ApprovalInstanceState(
                state.applicant, 4, ApprovalMode.N_OF_M, 2, state.approvers);
        approval.decide(state.firstApprover, ApprovalDecision.APPROVE);
        return approval.decide(state.secondApprover, ApprovalDecision.APPROVE);
    }
}
