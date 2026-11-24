#!/usr/bin/env bash
# Local end-to-end check of the AWS identity and routing wiring, without AWS: an nginx "edge"
# stands in for CloudFront + ALB path routing (/idp -> keycloak, /api and /actuator/health ->
# edge-gateway). Keycloak is the AWS image; erp-core and edge-gateway are the linux/arm64 runtime
# images configured with the same environment variables as the ECS task definitions (TLS
# switches excepted). Then tests/deployment/aws-smoke.sh runs against the edge URL.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
raw=$repo/docs/evidence/local-2026-11-23-aws-iac/raw
net=mf-awscd-e2e
pw=synthetic-local-only
public=http://localhost:28490
issuer=$public/idp/realms/materialflow
jwks=http://keycloak:8080/idp/realms/materialflow/protocol/openid-connect/certs
names=(mf-awscd-e2e-edge mf-awscd-e2e-gateway mf-awscd-e2e-core mf-awscd-e2e-kc mf-awscd-e2e-redis mf-awscd-e2e-pg)
cleanup() {
  for n in "${names[@]}"; do docker logs "$n" > "$raw/e2e-$n.log" 2>&1 || true; done
  docker rm -f "${names[@]}" >/dev/null 2>&1 || true
  docker network rm $net >/dev/null 2>&1 || true
  rm -f "${conf:-}"
}
trap cleanup EXIT
docker rm -f "${names[@]}" >/dev/null 2>&1 || true; docker network rm $net >/dev/null 2>&1 || true
docker network create $net >/dev/null
hardening=(--read-only --tmpfs /tmp --security-opt no-new-privileges:true --memory 1g)

docker run -d --name mf-awscd-e2e-pg --network $net --network-alias postgres -e POSTGRES_DB=materialflow \
  -e POSTGRES_USER=materialflow -e POSTGRES_PASSWORD=$pw postgres:17.11-alpine >/dev/null
docker run -d --name mf-awscd-e2e-redis --network $net --network-alias redis redis:8.2.1-alpine >/dev/null
until docker exec mf-awscd-e2e-pg pg_isready -U materialflow -d materialflow >/dev/null 2>&1; do sleep 1; done
sleep 2
docker run --rm --network $net -e PGHOST=postgres -e PGDATABASE=materialflow -e PGSSLMODE=disable \
  -e PGUSER=materialflow -e PGPASSWORD=$pw postgres:17.11-alpine \
  /bin/sh -ec "$(cat $repo/deploy/terraform/aws/scripts/create-keycloak-database.sh)"

docker run -d --name mf-awscd-e2e-kc --network $net --network-alias keycloak \
  -e KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak -e KC_DB_USERNAME=materialflow -e KC_DB_PASSWORD=$pw \
  -e KC_DB_POOL_MAX_SIZE=10 -e KC_HOSTNAME=$public/idp -e KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true \
  -e KC_HTTP_ENABLED=true -e KC_PROXY_HEADERS=xforwarded -e KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=$pw -e MATERIALFLOW_PUBLIC_URL=$public materialflow-awscd/keycloak:repo-realm >/dev/null

common=(-e MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true -e MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
  -e MATERIALFLOW_SECURITY_ENABLED=true -e "OIDC_ISSUER_URI=$issuer" -e "OIDC_JWK_SET_URI=$jwks")
docker run -d --name mf-awscd-e2e-core --network $net --network-alias erp-core "${hardening[@]}" "${common[@]}" \
  -e MATERIALFLOW_DB_URL=jdbc:postgresql://postgres:5432/materialflow -e MATERIALFLOW_DB_USER=materialflow \
  -e MATERIALFLOW_DB_PASSWORD=$pw -e MATERIALFLOW_DB_POOL_SIZE=10 -e MATERIALFLOW_REDIS_HOST=redis \
  -e MATERIALFLOW_REDIS_PORT=6379 -e MATERIALFLOW_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  materialflow-awscd/erp-core:arm64-runtime >/dev/null
docker run -d --name mf-awscd-e2e-gateway --network $net --network-alias edge-gateway "${hardening[@]}" "${common[@]}" \
  -e REDIS_HOST=redis -e MATERIALFLOW_REDIS_PORT=6379 -e ERP_CORE_URI=http://erp-core:8080 \
  -e PROJECTION_URI=http://stream-projection:8082 materialflow-awscd/edge-gateway:arm64-runtime >/dev/null

conf=$(mktemp)
cat > "$conf" <<'NGINX'
server {
  listen 8080;
  location /idp/              { proxy_pass http://keycloak:8080; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; }
  location /api/              { proxy_pass http://edge-gateway:8081; }
  location /actuator/health   { proxy_pass http://edge-gateway:8081; }
  location /                  { return 404; }
}
NGINX
chmod 644 "$conf"
docker run -d --name mf-awscd-e2e-edge --network $net -p 127.0.0.1:28490:8080 \
  -v "$conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.29-alpine >/dev/null

# The ECS container health check command from services.tf, run inside the actual images.
wait_healthy() {
  local name=$1 port=$2 t0
  t0=$(date +%s)
  until docker exec "$name" sh -c "wget -q -O /dev/null http://127.0.0.1:${port}/actuator/health/liveness || exit 1" 2>/dev/null; do
    sleep 2; if (( $(date +%s) - t0 > 180 )); then docker logs "$name" | tail -30; exit 1; fi
  done
  echo "$name: ECS liveness check command passes after $(( $(date +%s) - t0 ))s"
}
wait_healthy mf-awscd-e2e-core 8080
wait_healthy mf-awscd-e2e-gateway 8081
echo "readiness (ALB target group path): $(docker exec mf-awscd-e2e-gateway wget -q -O - http://127.0.0.1:8081/actuator/health/readiness)"

SMOKE_ATTEMPTS=24 MATERIALFLOW_PUBLIC_URL=$public bash $repo/tests/deployment/aws-smoke.sh
