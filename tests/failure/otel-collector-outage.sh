#!/usr/bin/env bash
set -euo pipefail

compose_file="${COMPOSE_FILE:-deploy/compose/compose.yml}"
project="${COMPOSE_PROJECT_NAME:-materialflow}"
core_url="${CORE_URL:-http://localhost:8080}"

docker compose -p "$project" -f "$compose_file" stop otel-collector
status=$(curl --silent --output /tmp/materialflow-otel-drill-health.json --write-out '%{http_code}' \
  "$core_url/actuator/health")
docker compose -p "$project" -f "$compose_file" start otel-collector
test "$status" = "200"
printf 'core_health_during_collector_outage=%s recovered=true\n' "$status"
