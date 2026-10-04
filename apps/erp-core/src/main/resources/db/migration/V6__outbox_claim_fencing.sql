-- Additive migration. Stop all old Relay instances before switching to token-aware Relay.
-- Existing claims keep a NULL token until their lease expires and they are reclaimed.
ALTER TABLE outbox_events ADD COLUMN claim_token UUID;

COMMENT ON COLUMN outbox_events.claim_token IS
    'Fresh UUID per claim; fences stale completions even after hostname reuse or attempt reset';

CREATE INDEX idx_outbox_expired_claim ON outbox_events (claimed_at, id)
    WHERE status = 'CLAIMED';
