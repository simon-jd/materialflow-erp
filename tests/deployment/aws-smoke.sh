#!/usr/bin/env bash
# Post-deploy smoke test through the public URL: the AWS environment (CloudFront) or the AKS one
# (Gateway API, docs/runbooks/azure-deploy.md); both serve /, /api, /idp and /actuator/health on one origin.
# Read-only: it creates no orders. Usage:
#   MATERIALFLOW_PUBLIC_URL=https://dxxxxxxxxxxxxx.cloudfront.net bash tests/deployment/aws-smoke.sh
# Set SMOKE_CONSOLE=true to also check the admin console and its runtime config.
set -euo pipefail

base=${MATERIALFLOW_PUBLIC_URL:?Set MATERIALFLOW_PUBLIC_URL to the CloudFront URL}
base=${base%/}
attempts=${SMOKE_ATTEMPTS:-30}
organization=11111111-1111-1111-1111-111111111111
actor=66666666-6666-6666-6666-666666666602
body=$(mktemp)
trap 'rm -f "$body"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

# CloudFront and freshly started tasks can take a few minutes to converge after a deploy.
retry() {
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if "$@"; then return 0; fi
    sleep 10
  done
  return 1
}

gateway_up() {
  curl -fsS "${base}/actuator/health" -o "$body" 2>/dev/null && jq -e '.status == "UP"' "$body" >/dev/null
}
retry gateway_up || fail "gateway health at ${base}/actuator/health never reported UP"
echo "gateway health: UP"

# Synthetic demo user from deploy/compose/keycloak/materialflow-realm.json; the token is never printed.
issue_token() {
  curl -fsS "${base}/idp/realms/materialflow/protocol/openid-connect/token" \
    --data-urlencode client_id=materialflow-cli --data-urlencode grant_type=password \
    --data-urlencode username=synthetic-demo --data-urlencode password=synthetic-demo-only \
    -o "$body" 2>/dev/null && jq -e '.access_token' "$body" >/dev/null
}
retry issue_token || fail "Keycloak did not issue a token at ${base}/idp"
token=$(jq -r '.access_token' "$body")
issuer=$(jq -rR 'split(".")[1] | gsub("-"; "+") | gsub("_"; "/") | . + ("=" * ((4 - length % 4) % 4))
  | @base64d | fromjson | .iss' <<<"$token" 2>/dev/null || true)
[[ $issuer == "${base}/idp/realms/materialflow" ]] || fail "token issuer '${issuer}' does not match ${base}/idp/realms/materialflow"
echo "token issuer: ${issuer}"

order_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
order_url="${base}/api/v1/orders/${order_id}"
identity=(-H "X-Organization-Id: ${organization}" -H "X-Actor-Id: ${actor}")

status=$(curl -sS -o /dev/null -w '%{http_code}' "${identity[@]}" "$order_url")
[[ $status == 401 ]] || fail "unauthenticated read returned ${status}, expected 401"
echo "unauthenticated read: 401"

# A random order ID must be a 404 from erp-core: the JWT passed the gateway and erp-core, the route
# resolved through Service Connect and PostgreSQL answered the lookup.
status=$(curl -sS -o "$body" -w '%{http_code}' "${identity[@]}" -H "Authorization: Bearer ${token}" "$order_url")
[[ $status == 404 ]] || fail "authenticated read returned ${status}, expected 404"
jq -e '.errorCode == "RESOURCE_NOT_FOUND"' "$body" >/dev/null || fail "unexpected 404 body: $(cat "$body")"
echo "authenticated read: 404 RESOURCE_NOT_FOUND"

if [[ ${SMOKE_CONSOLE:-false} == true ]]; then
  status=$(curl -sS -o "$body" -w '%{http_code}' "${base}/orders/${order_id}")
  if [[ $status != 200 ]] || ! grep -qi '<html' "$body"; then fail "console deep link returned ${status}"; fi
  status=$(curl -sS -o /dev/null -w '%{http_code}' "${base}/config.js")
  [[ $status == 200 ]] || fail "console runtime config returned ${status}"
  echo "admin console: index.html served for deep links, config.js present"
fi

echo "smoke test passed for ${base}"
