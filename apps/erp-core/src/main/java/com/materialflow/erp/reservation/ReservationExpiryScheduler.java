package com.materialflow.erp.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases reservations whose TTL elapsed, oldest first, one order per transaction.
 *
 * <p>An order whose release fails is retried with exponential backoff instead of ending the batch.
 * Without that, one order that can never be released stays first in line and blocks every expiry
 * behind it, which the 2026-11-24 AKS fault drills observed (13,306 reservations left unreleased).
 */
@Component
@ConditionalOnProperty(name = "materialflow.reservation.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {
    static final int BATCH_SIZE = 25;
    static final int MAX_QUARANTINED = 1_000;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(30);
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryScheduler.class);
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;
    private final ReservationReleaseApplicationService releases;
    private final Clock clock;
    private final Counter expired;
    private final Counter failed;
    private final Map<UUID, Quarantine> quarantined = new ConcurrentHashMap<>();

    @Autowired
    public ReservationExpiryScheduler(
            JdbcTemplate jdbc,
            ReservationReleaseApplicationService releases,
            MeterRegistry registry) {
        this(jdbc, releases, registry, Clock.systemUTC());
    }

    ReservationExpiryScheduler(
            JdbcTemplate jdbc,
            ReservationReleaseApplicationService releases,
            MeterRegistry registry,
            Clock clock) {
        this.jdbc = jdbc;
        this.releases = releases;
        this.clock = clock;
        this.expired = registry.counter("reservation_expired_total");
        this.failed = registry.counter("reservation_expiry_failed_total");
        Gauge.builder("reservation_expiry_quarantined_orders", quarantined, Map::size)
                .strongReference(true).register(registry);
    }

    @Scheduled(fixedDelayString = "${materialflow.reservation.expiry-poll-delay:30s}")
    public void expireBatch() {
        Instant now = clock.instant();
        Set<UUID> waiting = quarantined.entrySet().stream()
                .filter(entry -> entry.getValue().retryAt().isAfter(now))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        // Over-fetch by the number of orders still backing off so they cannot crowd out the batch.
        var candidates = jdbc.query("""
                SELECT organization_id, order_id
                  FROM inventory_reservations
                 WHERE status IN ('ACTIVE', 'PARTIALLY_SHIPPED') AND expires_at <= CURRENT_TIMESTAMP
                 GROUP BY organization_id, order_id
                 ORDER BY min(expires_at)
                 LIMIT ?
                """, (resultSet, rowNumber) -> new Candidate(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class)),
                BATCH_SIZE + waiting.size());
        int attempted = 0;
        for (Candidate candidate : candidates) {
            if (attempted == BATCH_SIZE) {
                break;
            }
            if (waiting.contains(candidate.orderId())) {
                continue;
            }
            attempted++;
            expire(candidate, now);
        }
    }

    private void expire(Candidate candidate, Instant now) {
        try {
            if (releases.expire(candidate.orderId(), candidate.organizationId(), SYSTEM_ACTOR)) {
                expired.increment();
            }
            quarantined.remove(candidate.orderId());
        } catch (RuntimeException exception) {
            failed.increment();
            Quarantine next = quarantined.get(candidate.orderId()) == null
                    ? Quarantine.first(now) : quarantined.get(candidate.orderId()).next(now);
            if (quarantined.containsKey(candidate.orderId()) || quarantined.size() < MAX_QUARANTINED) {
                quarantined.put(candidate.orderId(), next);
            }
            LOG.warn("Reservation expiry failed for order {} (attempt {}); next attempt after {}",
                    candidate.orderId(), next.attempts(), next.retryAt(), exception);
        }
    }

    record Candidate(UUID organizationId, UUID orderId) {
    }

    private record Quarantine(int attempts, Instant retryAt) {
        static Quarantine first(Instant now) {
            return new Quarantine(1, now.plus(FIRST_RETRY));
        }

        Quarantine next(Instant now) {
            Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(attempts, 10));
            return new Quarantine(attempts + 1, now.plus(delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay));
        }
    }
}
