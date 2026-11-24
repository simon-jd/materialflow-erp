#!/usr/bin/env bash
# Local check of the AWS Keycloak image and its init script: postgres + keycloak containers on a
# private network, configured like the ECS task definition (TLS to RDS replaced by sslmode=disable).
# Usage: keycloak-check.sh [realm.json]  (default: the repository realm)
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
raw=$repo/docs/evidence/local-2026-11-23-aws-iac/raw
realm=${1:-$repo/deploy/compose/keycloak/materialflow-realm.json}
label=${2:-repo-realm}
net=mf-awscd-kc
pw=synthetic-local-only
public=http://localhost:28480
cleanup() { docker rm -f mf-awscd-kc mf-awscd-pg >/dev/null 2>&1 || true; docker network rm $net >/dev/null 2>&1 || true; rm -rf "${ctx:-}"; }
trap cleanup EXIT
cleanup
# Build context with the same layout as the repository, so the realm under test can be swapped in.
ctx=$(mktemp -d)
mkdir -p "$ctx/deploy/docker" "$ctx/deploy/compose/keycloak"
cp $repo/deploy/docker/Dockerfile.keycloak $repo/deploy/docker/keycloak-realm-aws.jq "$ctx/deploy/docker/"
cp "$realm" "$ctx/deploy/compose/keycloak/materialflow-realm.json"
docker build --platform linux/arm64 -f "$ctx/deploy/docker/Dockerfile.keycloak" -t materialflow-awscd/keycloak:$label "$ctx" > $raw/keycloak-build-$label.log 2>&1
echo "image: $(docker image inspect --format '{{.Os}}/{{.Architecture}}' materialflow-awscd/keycloak:$label)"
docker network create $net >/dev/null
docker run -d --name mf-awscd-pg --network $net -e POSTGRES_DB=materialflow -e POSTGRES_USER=materialflow \
  -e POSTGRES_PASSWORD=$pw postgres:17.11-alpine >/dev/null
until docker exec mf-awscd-pg pg_isready -U materialflow -d materialflow >/dev/null 2>&1; do sleep 1; done
sleep 2
init() {
  docker run --rm --network $net -e PGHOST=mf-awscd-pg -e PGPORT=5432 -e PGDATABASE=materialflow -e PGSSLMODE=disable \
    -e PGUSER=materialflow -e PGPASSWORD=$pw postgres:17.11-alpine /bin/sh -ec "$(cat $repo/deploy/terraform/aws/scripts/create-keycloak-database.sh)"
}
echo "init#1: $(init)"
echo "init#2: $(init)"
start_kc() {
  docker run -d --name mf-awscd-kc --network $net -p 127.0.0.1:28480:8080 -p 127.0.0.1:29480:9000 \
    -e KC_DB_URL=jdbc:postgresql://mf-awscd-pg:5432/keycloak -e KC_DB_USERNAME=materialflow -e KC_DB_PASSWORD=$pw \
    -e KC_DB_POOL_MAX_SIZE=10 -e KC_HOSTNAME=$public/idp \
    -e KC_HTTP_ENABLED=true -e KC_PROXY_HEADERS=forwarded -e KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=$pw -e MATERIALFLOW_PUBLIC_URL=$public materialflow-awscd/keycloak:$label >/dev/null
  local t0; t0=$(date +%s)
  until curl -fsS http://127.0.0.1:29480/health/ready 2>/dev/null | grep -q '"UP"'; do
    sleep 2; if (( $(date +%s) - t0 > 240 )); then docker logs mf-awscd-kc | tail -40; exit 1; fi
  done
  echo "ready after $(( $(date +%s) - t0 ))s: $(curl -fsS http://127.0.0.1:29480/health/ready | tr -d '\n ')"
}
start_kc
token=$(curl -fsS $public/idp/realms/materialflow/protocol/openid-connect/token \
  --data-urlencode client_id=materialflow-cli --data-urlencode grant_type=password \
  --data-urlencode username=synthetic-demo --data-urlencode password=synthetic-demo-only | jq -er .access_token)
payload=$(cut -d. -f2 <<<"$token" | tr '_-' '/+'); while (( ${#payload} % 4 )); do payload+='='; done
base64 -d <<<"$payload" | jq -c '{iss, realm_roles: (.realm_access.roles | map(select(test("^[A-Z_]+$"))) | sort), organization_id, user_id, warehouse_scope}'
# JWKS at the exact in-VPC URL the resource servers use (Service Connect alias keycloak:8080).
docker run --rm --network $net curlimages/curl:8.11.1 -fsS \
  http://mf-awscd-kc:8080/idp/realms/materialflow/protocol/openid-connect/certs | jq -c '{jwks_keys: (.keys | length), algs: ([.keys[].alg] | sort)}'
echo "old /auth prefix is not Keycloak's: $(curl -s -o /dev/null -w '%{http_code}' $public/auth/realms/materialflow)"
echo "admin console path served by Keycloak (blocked at the ALB in AWS): $(curl -s -o /dev/null -w '%{http_code}' $public/idp/admin/)"
# Authorization request for the admin console client: 200 = redirect URI accepted, 400 = rejected.
authz() {
  curl -s -o /dev/null -w '%{http_code}' -G "$public/idp/realms/materialflow/protocol/openid-connect/auth" \
    --data-urlencode client_id=materialflow-admin-console --data-urlencode response_type=code --data-urlencode scope=openid \
    --data-urlencode code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM --data-urlencode code_challenge_method=S256 \
    --data-urlencode "redirect_uri=$1"
}
# Behind CloudFront the request Host is the ALB DNS name; discovery must still advertise the public URL.
echo "discovery with ALB Host header: $(curl -fsS -H 'Host: internal-alb.example.com' $public/idp/realms/materialflow/.well-known/openid-configuration | jq -c '{issuer, token_endpoint, jwks_uri}')"
# CloudFront adds "Forwarded: proto=https" on the ALB origin; Keycloak must then mark cookies Secure.
cookies() {
  curl -s -o /dev/null -D - -G "$public/idp/realms/materialflow/protocol/openid-connect/auth" "$@" \
    --data-urlencode client_id=materialflow-admin-console --data-urlencode response_type=code --data-urlencode scope=openid \
    --data-urlencode code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM --data-urlencode code_challenge_method=S256 \
    --data-urlencode "redirect_uri=$public/auth/callback" |
    tr -d '\r' | grep -i '^set-cookie:' | sed -E 's/^[Ss]et-[Cc]ookie: ([^=]+)=[^;]*/\1/' | sort | tr '\n' '|'
}
echo "cookies, Forwarded proto=https: $(cookies -H 'Forwarded: proto=https')"
echo "cookies, no Forwarded header:  $(cookies)"
echo "admin console authorize, public origin callback: $(authz "$public/auth/callback")"
echo "admin console authorize, foreign callback:       $(authz "https://attacker.example/auth/callback")"
docker logs mf-awscd-kc > $raw/keycloak-first-start-$label.log 2>&1
grep -E "Realm .* imported|started in" $raw/keycloak-first-start-$label.log | sed -E 's/^.*(Realm|Keycloak)/\1/' | head -2
docker rm -f mf-awscd-kc >/dev/null
start_kc
docker logs mf-awscd-kc > $raw/keycloak-restart-$label.log 2>&1
grep -E 'already exists' $raw/keycloak-restart-$label.log | sed -E 's/^.*(Realm)/\1/' | head -1
