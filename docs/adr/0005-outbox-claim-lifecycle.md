# ADR 0005: Fence each Outbox claim and make exhausted work terminal

Status: accepted, locally verified on 2026-09-06

## Problem and evidence

The baseline used hostname ownership alone. A restarted Relay using the same
hostname could reclaim an expired row while an old sender still held its ID.
That sender could then mark the new claim published or failed. Separately, a
process disappearing during its last permitted attempt left a `CLAIMED` row
that could never satisfy `attempt_count < max_attempts` again.

Both defects were reproduced against the baseline in real local PostgreSQL:
[negative control and verification](../evidence/local-2026-09-06/README.md).

## Decision

Each claim receives a fresh PostgreSQL-generated UUID `claim_token`. Completion
updates compare the event ID, instance ID, status and token. Failure updates
also match the claimed attempt number. Zero updated rows mean ownership was
lost; this is a counted concurrency outcome, not an exception that aborts the
remaining poll. SQL/database errors still propagate.

The token is an equality fence for one claim generation, not a distributed
monotonic sequence. Unlike using only `attempt_count`, it remains distinct after
an operator resets the retry budget. Lease expiry permits takeover; an expired
sender can still complete if nobody has reclaimed or terminalized the row.
It cannot overwrite a successor claim. This fence does not prevent a stale
sender from contacting Kafka.

At the start of each bounded poll, a `SKIP LOCKED` sweep terminalizes expired
claims whose retry budget is exhausted. Due PENDING/FAILED rows already at the
budget are also terminalized. Live final claims and future retries are left
alone. Dead-letter state retains an explicitly uncertain delivery outcome:
Kafka may already have accepted the record. Dead letters require inspection
and deliberate replay; bounded retries do not guarantee eventual delivery.

The sender claims one record immediately before sending it, up to `batch-size`
records per poll. The repository still supports disjoint batch claims, but the
serial sender does not hold leases for a queue of unsent records. This trades
additional database round trips for a smaller failure surface. There is no
throughput improvement claim. A large batch can still lengthen the poll and
delay the next sweep; tune against measured latency, not a claimed recovery SLO.

Kafka waits remain outside the claim/completion database transactions. A broker
ACK followed by a database write failure leaves the claim recoverable. It is
not relabeled a broker failure. An interrupted wait preserves the interrupt
flag, stops further claims and leaves the uncertain event for recovery.
Publish timeouts can also result in duplicates because the producer's own
delivery window may exceed the caller's wait.

Consumers continue to commit their Inbox marker and business side effects in
one PostgreSQL transaction. Success/duplicate metrics now run after transaction
commit, so a rolled-back allocation does not report success.

## Alternatives and limits

- Hostname-only ownership fails under name reuse; reproduced locally.
- Attempt number alone can repeat after a manual retry-budget reset.
- Holding row locks while awaiting Kafka ACK would consume database connections
  and violate the short-transaction boundary.
- Heartbeating a whole batch or parallel sends adds lease and shutdown machinery.
  It needs a measured throughput justification before adoption here.
- A CDC-based Relay would add infrastructure and a separate operating model.
  It does not remove the need for idempotent consumers.
- Kafka producer idempotence does not make a new application send with the same
  event payload globally unique. The broker integration test observes two offsets
  for the same event; cross-system exactly-once is not claimed.

## Migration and rollback

V6 adds a nullable column and an index without rewriting V1–V5 or the event
envelope. Relay tests now run the real ERP Flyway migrations rather than a
hand-maintained table imitation. Both empty-database migration and V5 upgrade
with an existing claim are verified.

Stop all old Relay binaries before starting token-aware ones. Apply V6 through
ERP Core/Flyway, wait for migration success, then start the new Relay. Existing
claims have no token and can be reclaimed after their lease expires. No expiry
timestamps are changed by the migration itself.

Mixed old/new Relays are unsafe: the old UPDATE does not check tokens. To roll
back, stop new Relays first, retain V6's additive schema and restart the previous
binary only as a deliberate reversion to its weaker semantics. Do not promise
a safe mixed-version rolling upgrade. Index creation is regular transactional
DDL suitable for this local dataset; online production migration timing has
not been measured.

## Observability

`outbox_claim_lost_total` counts rejected stale completions;
`outbox_exhausted_total` counts sweep transitions. `outbox_dead_letter_total`
counts completed transitions, not attempted stale writes. The database-backed
`outbox_dead_letter_count` survives process-counter resets; alerts use `max`
across replicas because each observes the same table. These gauges update after
completed polls and are not live database probes if polling itself fails.

Tests use real local PostgreSQL/Kafka with injected post-ACK failure and lease
timestamps. They are not a process-kill campaign, an actual offset-commit crash,
a multi-node partition test, or production/on-call evidence.
