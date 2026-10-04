# Concurrency and invariant report

## Executed concurrency cases

`OrderConcurrencyIntegrationTest` launched 20 contenders against one hot SKU.
The asserted terminal balance was `on_hand=1000`, `reserved=1000`,
`available=0`; no invalid inventory row was produced. The test also covers
idempotent replay and idempotency-key payload mismatch rejection.

`CreditAndApprovalConcurrencyIntegrationTest` exercised:

- a two-actor approval race with exactly one successful decision, one rejected
  duplicate/racing decision, and one stored decision;
- ten concurrent orders against a 5,000 credit limit, with four reservations and
  six `PENDING_APPROVAL` outcomes, without exceeding credit or inventory.

`ReservationExpiryShipmentRaceIntegrationTest` races expiry against shipment and
asserts exactly one legal terminal outcome. `BusinessFlowIntegrationTest` checks
duplicate invoice and payment-import protections along the business flow.

These are Testcontainers-backed PostgreSQL tests, not in-memory simulations.

## Final running-stack invariants

After the final three-run formal JMeter protocol and consumer catch-up,
PostgreSQL returned:

```json
{
  "orders": 55411,
  "outbox_total": 56786,
  "outbox_pending": 0,
  "consumer_inbox": 56786,
  "audit": 56786,
  "invalid_inventory": 0,
  "over_credit": 0,
  "duplicate_invoice_orders": 0
}
```

Both Kafka consumer groups were active at offset 56,786 of 56,786 with lag 0:

| Consumer group | Current offset | Log end | Lag |
|---|---:|---:|---:|
| `erp-core-reallocation-v1` | 56786 | 56786 | 0 |
| `materialflow-projection-v1` | 56786 | 56786 | 0 |

These results are measured against the synthetic local dataset. They demonstrate
the tested conditions only and are not a proof of correctness for every possible
schedule or production workload.
