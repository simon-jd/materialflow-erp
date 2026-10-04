# ADR 0003: Consistency, locking and idempotency

Status: accepted

Hot writes use MyBatis explicit row locks and version-checked updates. All paths
that touch both resources lock credit, then inventory in stable composite-key
order. Database constraints remain the final guard. HTTP idempotency stores a
canonical request hash and serialized response; reuse with different input is a
conflict. Reservation fencing tokens reject stale release or shipment operations.
This favors correctness and explainability over optimistic maximum throughput.
