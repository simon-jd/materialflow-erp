package com.materialflow.erp.events;

import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Pause point between a consumer side effect and the Kafka offset commit that follows it.
 *
 * <p>Neither window has a database write of its own that a fixture trigger could block, so the
 * process-kill drill ({@code tests/failure/process-kill-recovery.py}) needs an explicit hook to
 * hold the listener thread there while it SIGKILLs the JVM. Normal wiring uses {@link #NONE};
 * only the {@value #PROFILE} profile installs a gate that can pause.
 */
@FunctionalInterface
public interface ConsumerCrashWindowGate {
    String PROFILE = "fault-injection";

    /** Production gate: never inspects the record and never pauses. */
    ConsumerCrashWindowGate NONE = (window, record) -> { };

    void enter(Window window, ConsumerRecord<?, ?> record);

    enum Window {
        /** Inbox row and business update are committed; the record's offset is not. */
        AFTER_INBOX_COMMIT,
        /** Kafka acknowledged the dead-letter copy; the source record's offset is not committed. */
        AFTER_DEAD_LETTER_SEND;

        /** Record header that arms this window for one record under the fault-injection profile. */
        public String header() {
            return "materialflow-crash-gate-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
