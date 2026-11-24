package com.materialflow.erp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.materialflow.erp.events.ConsumerCrashWindowGate.Window;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class ConsumerCrashWindowGateTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("materialflow.kafka.consumer.enabled=true")
            .withUserConfiguration(KafkaConsumerConfiguration.class)
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(ConsumerFactory.class, () -> mock(ConsumerFactory.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void withoutTheProfileTheGateIsTheNoOpAndNeedsNoDatabase() {
        // No JdbcTemplate bean: wiring the advisory-lock gate here would fail the context.
        context.run(beans -> {
            assertThat(beans).hasNotFailed();
            var gate = beans.getBean(ConsumerCrashWindowGate.class);
            assertThat(gate).isSameAs(ConsumerCrashWindowGate.NONE);
            for (Window window : Window.values()) {
                gate.enter(window, armed(window, 42)); // Armed records pass straight through.
            }
        });
    }

    @Test
    void faultInjectionProfileInstallsTheAdvisoryLockGate() {
        context.withBean(JdbcTemplate.class, () -> jdbc)
                .withInitializer(beans -> beans.getEnvironment().setActiveProfiles(ConsumerCrashWindowGate.PROFILE))
                .run(beans -> assertThat(beans.getBean(ConsumerCrashWindowGate.class))
                        .isInstanceOf(AdvisoryLockCrashWindowGate.class));
    }

    @Test
    void armedRecordWaitsOnItsLockOnlyInTheArmedWindow() {
        var gate = new AdvisoryLockCrashWindowGate(jdbc);
        var record = armed(Window.AFTER_INBOX_COMMIT, 4242);
        gate.enter(Window.AFTER_DEAD_LETTER_SEND, record);
        verifyNoInteractions(jdbc);
        gate.enter(Window.AFTER_INBOX_COMMIT, record);
        verify(jdbc).queryForObject("SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, 4242L);
    }

    @Test
    void unarmedRecordNeverTouchesTheDatabase() {
        var gate = new AdvisoryLockCrashWindowGate(jdbc);
        for (Window window : Window.values()) {
            gate.enter(window, new ConsumerRecord<>("materialflow.domain-events.v1", 0, 7, "key", "value"));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void headerNamesMatchTheProcessKillDrill() {
        assertThat(Window.AFTER_INBOX_COMMIT.header()).isEqualTo("materialflow-crash-gate-after-inbox-commit");
        assertThat(Window.AFTER_DEAD_LETTER_SEND.header())
                .isEqualTo("materialflow-crash-gate-after-dead-letter-send");
    }

    private static ConsumerRecord<String, String> armed(Window window, long lockKey) {
        var record = new ConsumerRecord<>("materialflow.domain-events.v1", 0, 7, "key", "value");
        record.headers().add(window.header(), Long.toString(lockKey).getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
