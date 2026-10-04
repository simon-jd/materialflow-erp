# ADR 0004: Redis failure boundary

Status: accepted

Redis contains no authoritative inventory, credit, approval or finance data. The
Gateway fails sensitive writes closed with an explicit 503 if its shared limiter
cannot be reached. ERP Core remains correct and uses PostgreSQL idempotency even
when Redis is empty. Read degradation may be added behind a bounded local limiter;
it must never silently weaken protection for writes.
