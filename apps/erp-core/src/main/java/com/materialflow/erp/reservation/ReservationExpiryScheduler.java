package com.materialflow.erp.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "materialflow.reservation.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final ReservationReleaseApplicationService releases;
    private final Counter expired;

    public ReservationExpiryScheduler(
            JdbcTemplate jdbc,
            ReservationReleaseApplicationService releases,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.releases = releases;
        this.expired = registry.counter("reservation_expired_total");
    }

    @Scheduled(fixedDelayString = "${materialflow.reservation.expiry-poll-delay:30s}")
    public void expireBatch() {
        var candidates = jdbc.query("""
                SELECT organization_id, order_id
                  FROM inventory_reservations
                 WHERE status IN ('ACTIVE', 'PARTIALLY_SHIPPED') AND expires_at <= CURRENT_TIMESTAMP
                 GROUP BY organization_id, order_id
                 ORDER BY min(expires_at)
                 LIMIT 25
                """, (resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class)));
        for (Candidate candidate : candidates) {
            if (releases.expire(candidate.orderId(), candidate.organizationId(), SYSTEM_ACTOR)) {
                expired.increment();
            }
        }
    }

    private record Candidate(UUID organizationId, UUID orderId) {
    }
}
