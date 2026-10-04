# Outbox and Kafka recovery

Alert on `outbox_pending_count`, `outbox_oldest_event_age_seconds`, publish
failures, `outbox_dead_letter_count`, `outbox_claim_lost_total` and consumer lag. During an outage, confirm ERP writes still
commit and pending Outbox age rises. Do not edit aggregate state or manually mark
events published.

Restore Kafka, verify Relay ACKs make backlog and lag monotonically fall, and
confirm Inbox duplicate counts may rise without repeated effects. A dead-lettered
record is replayed only after classifying its error and recording operator, reason,
event id and destination. Check whether delivery already occurred; dead letter
does not mean Kafka never accepted it. Stop conflicting operator replays, record
the prior attempt count and error in the operator audit, and condition the update
on the reviewed `DEAD_LETTER` row. Reset it to `FAILED` with `attempt_count = 0`,
NULL `claimed_by`, `claimed_at` and `claim_token`, and a future `next_attempt_at`.
Keep the same event id and payload. Merely setting `FAILED` without resetting the
exhausted budget cannot make the row eligible. No automated replay/admin API or
operator-audit workflow is implemented. The executable local outage drill is
`tests/failure/kafka-outage-recovery.sh`.

V6 and the new Relay require a coordinated switch from old binaries; see
[claim lifecycle ADR](../adr/0005-outbox-claim-lifecycle.md). The local regression
suite is `mvn -pl apps/event-relay -am verify`. `batch-size` caps work per poll;
the serial Relay claims each event immediately before publishing it.

`OutboxDeadLetterDetected` reads the database-backed gauge so a restart does not
hide unresolved dead letters. `OutboxClaimOwnershipLost` indicates an expired or
replaced claim: inspect instance overlap, poll timing and Kafka ACK delays.
Both pending and dead-letter gauges refresh at the end of completed polls;
also inspect Relay logs/health when database access fails.
