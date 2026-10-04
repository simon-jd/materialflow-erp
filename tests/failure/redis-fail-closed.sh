#!/usr/bin/env bash
set -euo pipefail

compose_file="${COMPOSE_FILE:-deploy/compose/compose.yml}"
project="${COMPOSE_PROJECT_NAME:-materialflow}"
gateway_url="${GATEWAY_URL:-http://localhost:8081}"
idempotency_key="redis-drill-$(date +%s)"

docker compose -p "$project" -f "$compose_file" stop redis
status=$(curl --silent --output /tmp/materialflow-redis-drill.json --write-out '%{http_code}' \
  -X POST "$gateway_url/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Organization-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-Actor-Id: 66666666-6666-6666-6666-666666666601' \
  -H 'X-Roles: SALES_ORDER_WRITE' \
  --data-binary '{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}')
docker compose -p "$project" -f "$compose_file" start redis

test "$status" = "503"
printf 'sensitive_write_status=%s recovered=true\n' "$status"
