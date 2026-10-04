# ADR 0002: At-least-once events with Outbox and Inbox

Status: accepted

The core writes an Outbox record in the same transaction as business state. Relay
instances atomically claim disjoint batches with `FOR UPDATE SKIP LOCKED`, publish
outside the transaction, then record ACK or bounded backoff. Consumers use the
unique `(event_id, consumer_name)` Inbox key in the side-effect transaction.
Duplicates around producer ACK and offset commit are therefore expected and safe.
The project does not claim cross-system exactly-once delivery.
