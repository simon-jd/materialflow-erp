# ADR 0006: Preserve recoverable database work at the consumer boundary

Date: 2026-09-07. Status: implemented; verification is linked below.

## Problem

The consumer previously used one four-retry budget for all listener failures.
A valid message encountering repeated database lock/connection failures could be
quarantined after five deliveries. Broker ACK and offset progress would then hide
an outstanding business effect until an operator deliberately replayed the DLQ.
Malformed JSON and missing IDs instead consumed those same retries despite being
unchangeable input errors.

## Decision

Use Spring Kafka's exception-specific BackOff with the existing synchronous
RECORD-mode consumer and transactional Inbox:

- Transient/recoverable data-access exceptions, JDBC connection acquisition and
  transaction creation failures retry without an attempt budget; delays are
  500 ms, 1 s, 2 s, 4 s, then capped at 5 s. Traverse wrapped causes.
- `InvalidEventEnvelopeException` identifies parsing and required UUID failures.
  It goes directly to acknowledged DLQ publication. This is not a full event
  schema validator; unknown event types remain forward-compatible no-ops.
- Other application failures retain the original four-retry policy. Do not mark
  every `IllegalArgumentException` as malformed input or every database error as
  recoverable; schema/constraint defects retain bounded quarantine behavior.
- DLQ send failure throws and preserves source replay. Waiting for the send was
  already the framework's default. Explicitly retain it and remove the misleading
  MANUAL_IMMEDIATE-only `commitRecovered` setting from our RECORD configuration.
- Emit failure/recovery counters tagged only by `database`, `invalid` or `other`.
  A successful recovery counter means DLQ ACK, not completed business work.

## Alternatives and costs

A separate retry topic would reduce partition blocking but require another replay
and ordering design. Kafka transactions can couple DLQ send/offset, but cannot
atomically commit PostgreSQL business effects. Both are unnecessary to repair the
immediate database retry boundary. The existing Inbox remains essential.

The selected policy can block later records and other partitions assigned to the
same listener container. Long-lived database credentials/configuration problems
can also retry indefinitely. Operators must restore dependencies, watch group lag
and Kafka retention headroom, and explicitly handle irreparable work. Five seconds
caps the retry delay, not the duration of an underlying database call or outage.
There is no measured recovery-time SLA or guarantee beyond retained source data.

DLQ ACK and source offset commit are separate operations; restart in between may
produce duplicate DLQ records. Identify them by original topic/partition/offset
(and eventId when valid). Success metrics can be lost on process death; counters
are operational observations, not durable accounting or a DLQ backlog gauge.

## Verification

[September 7 evidence](../evidence/local-2026-09-07/README.md) separates a real
PostgreSQL lock fault, missing Kafka topic and real consumer restart from unit
classification checks. Consumer objects run in the JUnit JVM, use the production
factory and listener, and call the proxied Inbox service with real Flyway schema.
They do not establish a JVM-kill, hosted CI, cloud or production result.

The real PostgreSQL fault exposed SQLSTATE `55P03` (`lock_not_available`) wrapped
as `UncategorizedSQLException` by the current JDBC translator. The policy handles
this specific code through wrapped causes; it does not match error text or classify
all SQL failures as transient. [PostgreSQL 17 error codes](https://www.postgresql.org/docs/17/errcodes-appendix.html)
recommend code-based classification. Schema/constraint errors retain bounded recovery.
