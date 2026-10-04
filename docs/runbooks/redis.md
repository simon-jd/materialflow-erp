# Redis outage

Expected behavior: Gateway sensitive writes return 503, while PostgreSQL remains
unchanged and authoritative. Inspect Gateway Redis error metrics and Redis health,
restore the service, then verify rate-limited writes recover. Never bypass Gateway
protection as an outage workaround. The local drill is
`tests/failure/redis-fail-closed.sh`.
