ALTER TABLE outbox_events
    ADD COLUMN trace_parent VARCHAR(55);

COMMENT ON COLUMN outbox_events.trace_parent IS
    'W3C traceparent captured with the business transaction and propagated by the relay';
