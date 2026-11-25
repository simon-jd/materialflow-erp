package com.materialflow.erp.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.materialflow.erp.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReservationExpirySchedulerTest {
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReservationReleaseApplicationService releases = mock(ReservationReleaseApplicationService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
    private ReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReservationExpiryScheduler(jdbc, releases, meters, clock);
    }

    @Test
    void aFailingOrderDoesNotStopTheRestOfTheBatch() {
        UUID poison = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        candidates(poison, healthy);
        when(releases.expire(eq(poison), eq(ORGANIZATION), any()))
                .thenThrow(BusinessException.conflict("CREDIT_RELEASE_INVALID", "Cannot release more credit"));
        when(releases.expire(eq(healthy), eq(ORGANIZATION), any())).thenReturn(true);

        scheduler.expireBatch();

        verify(releases).expire(eq(healthy), eq(ORGANIZATION), any());
        assertThat(meters.get("reservation_expired_total").counter().count()).isEqualTo(1);
        assertThat(meters.get("reservation_expiry_failed_total").counter().count()).isEqualTo(1);
        assertThat(meters.get("reservation_expiry_quarantined_orders").gauge().value()).isEqualTo(1);
    }

    @Test
    void aFailedOrderBacksOffAndIsRetriedOnceTheDelayElapses() {
        UUID poison = UUID.randomUUID();
        candidates(poison);
        when(releases.expire(eq(poison), eq(ORGANIZATION), any()))
                .thenThrow(BusinessException.conflict("CREDIT_RELEASE_INVALID", "Cannot release more credit"))
                .thenReturn(true);

        scheduler.expireBatch();
        clock.advance(Duration.ofSeconds(30));
        scheduler.expireBatch();
        verify(releases, times(1)).expire(eq(poison), eq(ORGANIZATION), any());

        clock.advance(ReservationExpiryScheduler.FIRST_RETRY);
        scheduler.expireBatch();
        verify(releases, times(2)).expire(eq(poison), eq(ORGANIZATION), any());
        assertThat(meters.get("reservation_expiry_quarantined_orders").gauge().value()).isZero();
    }

    @Test
    void ordersStillBackingOffCannotCrowdOutAFullBatch() {
        List<UUID> poison = IntStream.range(0, ReservationExpiryScheduler.BATCH_SIZE)
                .mapToObj(index -> UUID.randomUUID()).toList();
        candidates(poison.toArray(UUID[]::new));
        poison.forEach(orderId -> when(releases.expire(eq(orderId), eq(ORGANIZATION), any()))
                .thenThrow(BusinessException.conflict("CREDIT_RELEASE_INVALID", "Cannot release more credit")));
        scheduler.expireBatch();

        UUID healthy = UUID.randomUUID();
        UUID[] next = poison.toArray(new UUID[ReservationExpiryScheduler.BATCH_SIZE + 1]);
        next[ReservationExpiryScheduler.BATCH_SIZE] = healthy;
        candidates(next);
        when(releases.expire(eq(healthy), eq(ORGANIZATION), any())).thenReturn(true);
        scheduler.expireBatch();

        verify(releases).expire(eq(healthy), eq(ORGANIZATION), any());
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(2 * ReservationExpiryScheduler.BATCH_SIZE));
        poison.forEach(orderId -> verify(releases, times(1)).expire(eq(orderId), eq(ORGANIZATION), any()));
    }

    private void candidates(UUID... orderIds) {
        List<ReservationExpiryScheduler.Candidate> rows = List.of(orderIds).stream()
                .map(orderId -> new ReservationExpiryScheduler.Candidate(ORGANIZATION, orderId)).toList();
        doReturn(rows).when(jdbc).query(anyString(), any(RowMapper.class), anyInt());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
