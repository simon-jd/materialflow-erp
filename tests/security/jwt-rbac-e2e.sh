#!/usr/bin/env bash
set -euo pipefail

gateway_url=${MATERIALFLOW_GATEWAY_URL:-http://localhost:8081}
keycloak_url=${MATERIALFLOW_KEYCLOAK_URL:-http://localhost:8180}
organization=11111111-1111-1111-1111-111111111111
actor=66666666-6666-6666-6666-666666666601
warehouse=22222222-2222-2222-2222-222222222222
spoofed_warehouse=99999999-9999-9999-9999-999999999997
response_file=$(mktemp)
trap 'rm -f "$response_file"' EXIT

token() {
  local username=$1
  local password=$2
  curl -fsS "${keycloak_url}/realms/materialflow/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'client_id=materialflow-cli' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}" | jq -er '.access_token'
}

order_body='{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}'

unauthenticated=$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "${gateway_url}/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: security-unauthenticated-$(date +%s%N)" \
  -H "X-Organization-Id: ${organization}" -H "X-Actor-Id: ${actor}" \
  -H "X-Warehouse-Scope: ${warehouse}" -d "$order_body")
[[ $unauthenticated == 401 ]]

writer_token=$(token synthetic-demo synthetic-demo-only)
readonly_token=$(token synthetic-readonly synthetic-readonly-only)
other_tenant_token=$(token synthetic-other-tenant synthetic-other-only)

created=$(curl -sS -o "$response_file" -w '%{http_code}' \
  -X POST "${gateway_url}/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${writer_token}" \
  -H "Idempotency-Key: security-authorized-$(date +%s%N)" \
  -H "X-Organization-Id: 99999999-9999-9999-9999-999999999999" \
  -H "X-Actor-Id: 99999999-9999-9999-9999-999999999998" \
  -H 'X-Roles: ADMIN' -H "X-Warehouse-Scope: ${spoofed_warehouse}" \
  -d "$order_body")
[[ $created == 201 ]]
order_id=$(jq -er '.id' "$response_file")

forbidden=$(curl -sS -o "$response_file" -w '%{http_code}' \
  -X POST "${gateway_url}/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${readonly_token}" \
  -H "Idempotency-Key: security-forbidden-$(date +%s%N)" \
  -H "X-Organization-Id: ${organization}" -H "X-Actor-Id: ${actor}" \
  -H 'X-Roles: SALES_ORDER_WRITE' -H "X-Warehouse-Scope: ${warehouse}" \
  -d "$order_body")
[[ $forbidden == 403 ]]
[[ $(jq -r '.errorCode' "$response_file") == ACCESS_DENIED ]]

cross_tenant=$(curl -sS -o "$response_file" -w '%{http_code}' \
  "${gateway_url}/api/v1/orders/${order_id}" \
  -H "Authorization: Bearer ${other_tenant_token}" \
  -H "X-Organization-Id: ${organization}" -H "X-Actor-Id: ${actor}" \
  -H 'X-Roles: SALES_ORDER_WRITE' -H "X-Warehouse-Scope: ${warehouse}")
[[ $cross_tenant == 404 ]]

printf 'unauthenticated=%s authorized=%s forbidden=%s cross_tenant=%s order_id=%s\n' \
  "$unauthenticated" "$created" "$forbidden" "$cross_tenant" "$order_id"
