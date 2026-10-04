# PostgreSQL locks and pool pressure

Inspect `pg_stat_activity`, `pg_locks`, blocked PID, transaction age and Hikari
active/pending counts. Cancel a known synthetic drill session before terminating a
backend; do not kill an unidentified production session. Client timeouts can occur
before a transaction finishes, so retries must retain the same idempotency key.
The executable drill `tests/failure/postgres-lock-timeout.sh` demonstrates this
case and verifies a safe replay.
