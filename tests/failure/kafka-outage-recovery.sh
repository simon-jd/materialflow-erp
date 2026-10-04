#!/usr/bin/env bash
set -euo pipefail

compose_file="${COMPOSE_FILE:-deploy/compose/compose.yml}"
project="${COMPOSE_PROJECT_NAME:-materialflow}"
core_url="${CORE_URL:-http://localhost:8080}"
idempotency_key="kafka-drill-$(date +%s)"

docker compose -p "$project" -f "$compose_file" stop kafka
curl --fail-with-body --silent --show-error -X POST "$core_url/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Organization-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-Actor-Id: 66666666-6666-6666-6666-666666666601' \
  -H 'X-Roles: SALES_ORDER_WRITE' \
  -H 'X-Warehouse-Scope: 22222222-2222-2222-2222-222222222222' \
  --data-binary '{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}' >/tmp/materialflow-kafka-drill-order.json

pending_before=$(docker compose -p "$project" -f "$compose_file" exec -T postgres \
  psql -U materialflow -d materialflow -Atc "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'")
test "$pending_before" -gt 0
docker compose -p "$project" -f "$compose_file" start kafka

for attempt in $(seq 1 60); do
  remaining=$(docker compose -p "$project" -f "$compose_file" exec -T postgres \
    psql -U materialflow -d materialflow -Atc "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'")
  if [ "$remaining" -eq 0 ]; then
    printf 'recovered=true pending_before=%s polls=%s\n' "$pending_before" "$attempt"
    exit 0
  fi
  sleep 1
done

printf 'recovered=false pending_before=%s remaining=%s\n' "$pending_before" "$remaining" >&2
exit 1
