#!/usr/bin/env bash
# Starts (or removes) the smallest JWT-enabled stack the console e2e needs: PostgreSQL, Redis, Kafka,
# Keycloak, erp-core, edge-gateway and the console image, on the loopback-only qualification ports.
# Application images package the JARs from the last Maven build (run `mvn -B -ntp package` first).
#
#   apps/admin-console/e2e/stack.sh up|down
#   E2E_PROJECT (default materialflow-console-e2e), E2E_TAG (default console-e2e),
#   E2E_ENV_FILE (default deploy/compose/qualification.env)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
project=${E2E_PROJECT:-materialflow-console-e2e}
tag=${E2E_TAG:-console-e2e}
env_file=${E2E_ENV_FILE:-${root}/deploy/compose/qualification.env}
compose=(docker compose --env-file "${env_file}" -p "${project}" --profile console
  -f "${root}/deploy/compose/compose.yml" -f "${root}/deploy/compose/qualification.yml")
services=(postgres redis kafka kafka-init keycloak erp-core edge-gateway admin-console)

# shellcheck source=/dev/null
set -a && source "${env_file}" && set +a

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null "${url}"; then
      echo "${name} ready"
      return 0
    fi
    sleep 2
  done
  echo "${name} did not become ready: ${url}" >&2
  "${compose[@]}" ps >&2
  return 1
}

case "${1:-}" in
  up)
    # Packages the exact JARs from the last Maven build; no second compilation.
    bash "${root}/tests/deployment/build-runtime-images.sh" "${tag}" >/dev/null
    docker build -q -t "materialflow-qualification/admin-console:${tag}" "${root}/apps/admin-console"
    QUALIFICATION_TAG=${tag} "${compose[@]}" up -d --no-build "${services[@]}"
    wait_for keycloak "http://localhost:${MATERIALFLOW_KEYCLOAK_PORT}/realms/materialflow/.well-known/openid-configuration"
    wait_for erp-core "http://localhost:${MATERIALFLOW_ERP_PORT}/actuator/health/readiness"
    wait_for edge-gateway "http://localhost:${MATERIALFLOW_GATEWAY_PORT}/actuator/health"
    wait_for admin-console "http://localhost:${MATERIALFLOW_ADMIN_CONSOLE_PORT}/healthz"
    echo "E2E_BASE_URL=http://localhost:${MATERIALFLOW_ADMIN_CONSOLE_PORT}"
    ;;
  down)
    QUALIFICATION_TAG=${tag} "${compose[@]}" down -v --remove-orphans
    ;;
  *)
    echo "Usage: $0 up|down" >&2
    exit 2
    ;;
esac
