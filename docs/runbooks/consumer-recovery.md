# Domain-event consumer recovery

See [ADR 0006](../adr/0006-consumer-failure-classification.md) for the failure and
offset contract. Domain topic defaults to `materialflow.domain-events.v1`; group
is `erp-core-reallocation-v1`; consumer DLQ is the source topic plus `.DLQ`.
The Relay's PostgreSQL `DEAD_LETTER` rows and the Streams projection DLQ are
separate recovery paths.

A killed consumer's partition is reassigned once its group session expires:
`MATERIALFLOW_KAFKA_CONSUMER_SESSION_TIMEOUT` (default `10s`) and
`MATERIALFLOW_KAFKA_CONSUMER_HEARTBEAT_INTERVAL` (default `3s`). Raise the timeout
if unexpected rebalances follow GC pauses or CPU throttling. Set
`MATERIALFLOW_KAFKA_CONSUMER_GROUP_INSTANCE_ID` only for a replica whose identity
survives restarts and rollouts; see [ADR 0011](../adr/0011-consumer-failure-detection.md).

Provision the consumer DLQ before enabling consumers, with at least as many
partitions as the source and suitable replication/retention. Missing-DLQ recovery
blocks progress intentionally. Configure access for the application to publish to
it. Local Compose auto-creation is not a production provisioning mechanism.

## Database retry alert

`DomainConsumerDatabaseRetrying` detects continuing failure observations. Inspect
PostgreSQL connectivity/pool acquisition, locks, connection limits and credentials.
For the configured broker/group, inspect Kafka consumer-group lag and retention
headroom using `kafka-consumer-groups.sh --bootstrap-server <broker> --describe
--group erp-core-reallocation-v1`. Check PostgreSQL `pg_stat_activity`,
`pg_blocking_pids(pid)` and existing lock runbook. Restore the dependency and
confirm Inbox/business effects commit before group offset progresses.

Do not reset group offsets forward merely to make lag disappear. The interval
caps at five seconds, but attempts are intentionally unbounded for classified
recoverable database failures. A record can stall other assigned work. Query and
pool timeouts, retention capacity and operational recovery time must be qualified
for the target deployment. This patch does not supply a capacity/latency SLA.

## DLQ alerts

`DomainConsumerRecoveryFailed`: inspect topic existence, permissions, partition
layout and broker health. `event_consumer_recovered_total` must not be read as
business success; it records acknowledged quarantine. `DomainConsumerRecordQuarantined`
means inspect the DLQ and decide how to repair/replay. These counter-based rules
report recent observations, not persistent backlog; inspect actual topic contents
and retained offsets when a process restarted or an alert has resolved.

Preserve original topic, partition, offset, key, payload and tracing headers for
review. Fix producer/schema/application defects before deliberate replay; keep a
valid eventId for idempotent Inbox processing. Deduplicate DLQ review using original
topic/partition/offset because ACK-before-offset failure may duplicate quarantine.
No automatic audited DLQ replay API is implemented. Do not reinterpret damaged
identity fields or claim exactly-once cross-system delivery.

## Reproduce locally

```bash
mvn -B -ntp -pl apps/erp-core -am \
  -Dtest=DomainEventFailurePolicyTest,DomainEventConsumerRecoveryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Tests create ephemeral Kafka/PostgreSQL containers, disable broker topic
auto-creation, use synthetic records and verify actual group offsets via Admin.
The locked-table case exceeds the old retry budget, then releases the lock.
The missing-DLQ case stops/recreates the consumer object with the same group,
provisions the DLQ and observes replay. This is a consumer restart, not a JVM kill.

The separate [process-kill fault matrix](../../tests/failure/README.md) uses actual
SIGKILL on the deployed Core before Inbox commit, after Inbox commit but before the
source offset commit, and after DLQ ACK but before the source offset commit, then
checks retained offsets, restart, Inbox absorption and DLQ duplication.
