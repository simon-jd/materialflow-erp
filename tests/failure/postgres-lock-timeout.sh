#!/usr/bin/env bash
set -euo pipefail

compose_file="${COMPOSE_FILE:-deploy/compose/compose.yml}"
project="${COMPOSE_PROJECT_NAME:-materialflow}"
core_url="${CORE_URL:-http://localhost:8080}"
idempotency_key="db-lock-drill-$(date +%s)"

docker compose -p "$project" -f "$compose_file" exec -T postgres \
  psql -U materialflow -d materialflow -v ON_ERROR_STOP=1 -c \
  "BEGIN; SELECT id FROM credit_accounts WHERE customer_id='33333333-3333-3333-3333-333333333333' FOR UPDATE; SELECT pg_sleep(5); COMMIT;" \
  >/tmp/materialflow-db-lock-holder.log &
lock_pid=$!
sleep 1

set +e
curl --max-time 2 --silent --show-error -X POST "$core_url/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Organization-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-Actor-Id: 66666666-6666-6666-6666-666666666601' \
  -H 'X-Roles: SALES_ORDER_WRITE' \
  --data-binary '{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}' \
  >/tmp/materialflow-db-lock-first.json
first_exit=$?
set -e
wait "$lock_pid"

test "$first_exit" -ne 0
curl --fail-with-body --silent --show-error -X POST "$core_url/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $idempotency_key" \
  -H 'X-Organization-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-Actor-Id: 66666666-6666-6666-6666-666666666601' \
  -H 'X-Roles: SALES_ORDER_WRITE' \
  --data-binary '{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}' \
  >/tmp/materialflow-db-lock-retry.json
records=$(docker compose -p "$project" -f "$compose_file" exec -T postgres \
  psql -U materialflow -d materialflow -Atc "SELECT count(*) FROM idempotency_records WHERE operation='sales-order.create' AND idempotency_key='$idempotency_key' AND status='COMPLETED'")
test "$records" -eq 1
printf 'client_timeout_exit=%s idempotent_retry=success completed_records=%s\n' "$first_exit" "$records"
