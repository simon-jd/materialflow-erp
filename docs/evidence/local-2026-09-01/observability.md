# Observability evidence

## Health and metrics

At final collection, all five application health endpoints returned `UP`:
ERP Core, Edge Gateway, Stream Projection, Integration Adapter, and Event Relay.
Grafana reported database `ok` at version 12.2.0, and the Keycloak materialflow
issuer metadata endpoint was available.

Prometheus reported six active targets, all `up`, with empty `lastError` values:

- `edge-gateway`
- `erp-core`
- `event-relay`
- `integration-adapter`
- `stream-projection`
- `otel-collector`

The repository includes a provisioned Grafana overview dashboard and Prometheus
alert rules for service availability, errors, latency, Outbox backlog, and JVM
signals.

## Full transaction-to-consumer trace evidence

Synthetic order ID: `834ac2c5-1557-43cb-8371-da792d63c24b`

Outbox event ID: `04a34e7a-1e11-44cb-bffe-a804fd2fccb7`

Jaeger trace ID: `beffaaa5e624c43a9bb3f07385e98e4e`

The captured trace contains 16 spans from `materialflow-edge-gateway`,
`materialflow-erp-core`, and `materialflow-event-relay`. The critical parent
chain is:

```text
HTTP POST
  -> sales order database transaction (span 81830c6d21c7de09)
    -> outbox publish (span 52b091132da148d5)
      -> materialflow.domain-events.v1 send (span 2ed99bc8df7c1af6)
        -> domain event consume (span b21075143f34e377)
```

The Outbox row stored W3C context
`00-beffaaa5e624c43a9bb3f07385e98e4e-81830c6d21c7de09-01`, reached
`PUBLISHED`, and the same event ID was stored by
`erp-core-backorder-reallocation-v1` in `consumer_inbox`. This demonstrates one
continuous trace from secured API handling through the local PostgreSQL
transaction, durable Outbox, Kafka producer, and consumer.

The raw Jaeger response is
`raw/trace-beffaaa5e624c43a9bb3f07385e98e4e.json` (ignored by Git as raw local
evidence); SHA-256 is
`4a70e235aae436cf4ed5ef6b718657656afb8f331b4968572d7a705ee8c256e5`.
