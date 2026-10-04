#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
jmeter_home=${JMETER_HOME:-/tmp/apache-jmeter-5.6.3}
jmeter=${jmeter_home}/bin/jmeter
results_dir=${1:?Usage: run-formal-baseline.sh NEW_RESULTS_DIRECTORY}
runs=${MATERIALFLOW_PERF_RUNS:-3}
warmup_seconds=${MATERIALFLOW_PERF_WARMUP_SECONDS:-600}
measure_seconds=${MATERIALFLOW_PERF_MEASURE_SECONDS:-1200}
threads=${MATERIALFLOW_PERF_THREADS:-4}
rate_per_minute=${MATERIALFLOW_PERF_RATE_PER_MINUTE:-600}
gateway_port=${MATERIALFLOW_GATEWAY_PORT:-8081}
keycloak_port=${MATERIALFLOW_KEYCLOAK_PORT:-8180}
compose_project=${COMPOSE_PROJECT_NAME:-materialflow}
core_port=${MATERIALFLOW_ERP_PORT:-8080}
token_file=$(mktemp)
monitor_pid=

cleanup() {
  if [[ -n ${monitor_pid} ]]; then
    kill "${monitor_pid}" 2>/dev/null || true
    wait "${monitor_pid}" 2>/dev/null || true
  fi
  rm -f "${token_file}"
}
trap cleanup EXIT

if [[ ! -x ${jmeter} ]]; then
  printf 'JMeter is not executable at %s\n' "${jmeter}" >&2
  exit 1
fi
if [[ -e ${results_dir} ]]; then
  printf 'Refusing to overwrite existing performance evidence: %s\n' "${results_dir}" >&2
  exit 1
fi
mkdir -p "${results_dir}"
chmod 600 "${token_file}"

get_token() {
  local attempt response
  for attempt in $(seq 1 30); do
    if response=$(curl -fsS "http://localhost:${keycloak_port}/realms/materialflow/protocol/openid-connect/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'client_id=materialflow-cli' \
        --data-urlencode 'grant_type=password' \
        --data-urlencode 'username=synthetic-demo' \
        --data-urlencode 'password=synthetic-demo-only'); then
      printf '%s' "${response}" | jq -er '.access_token' >"${token_file}"
      return
    fi
    sleep 2
  done
  printf 'Unable to obtain the synthetic performance-test token\n' >&2
  exit 1
}

capture_database() {
  local output=$1
  docker exec "${compose_project}-postgres-1" psql -U materialflow -d materialflow -Atc \
    "select 'orders=' || count(*) from sales_orders;
     select 'outbox_pending=' || count(*) from outbox_events where status <> 'PUBLISHED';
     select 'lock_waiters=' || count(*) from pg_stat_activity where wait_event_type = 'Lock';
     select 'deadlocks=' || deadlocks || ',commits=' || xact_commit || ',rollbacks=' || xact_rollback || ',blks_hit=' || blks_hit || ',blks_read=' || blks_read from pg_stat_database where datname = 'materialflow';" \
    >"${output}"
}

monitor() {
  local output=$1
  while true; do
    printf 'timestamp=%s\n' "$(date -u +%FT%TZ)" >>"${output}"
    docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}} net={{.NetIO}}' \
      "${compose_project}-edge-gateway-1" "${compose_project}-erp-core-1" "${compose_project}-event-relay-1" \
      "${compose_project}-postgres-1" "${compose_project}-kafka-1" >>"${output}" 2>&1 || true
    docker exec "${compose_project}-postgres-1" psql -U materialflow -d materialflow -Atc \
      "select 'lock_waiters=' || count(*) from pg_stat_activity where wait_event_type = 'Lock';" \
      >>"${output}" 2>&1 || true
    curl -fsS "http://localhost:${core_port}/actuator/metrics/hikaricp.connections.active" \
      -H "Authorization: Bearer $(<"${token_file}")" \
      | jq -c '{metric:.name,value:(.measurements[0].value // null)}' >>"${output}" 2>/dev/null || true
    sleep 30
  done
}

summarize_jtl() {
  python3 "${project_root}/tests/performance/summarize_jtl.py" "$1" "$2" >"$3"
}

{
  printf 'started_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'runs=%s warmup_seconds=%s measure_seconds=%s threads=%s rate_per_minute=%s\n' \
    "${runs}" "${warmup_seconds}" "${measure_seconds}" "${threads}" "${rate_per_minute}"
  uname -a
  lscpu | sed -n '1,24p'
  free -h
  java -version 2>&1
  "${jmeter}" --version 2>&1
  docker ps --filter "label=com.docker.compose.project=${compose_project}"
} >"${results_dir}/environment.txt"
capture_database "${results_dir}/database-before.txt"

for run in $(seq 1 "${runs}"); do
  printf 'run=%s phase=warmup started=%s\n' "${run}" "$(date -u +%FT%TZ)"
  get_token
  "${jmeter}" -n -t "${project_root}/tests/performance/orders-load.jmx" \
    -Jhost=localhost -Jport="${gateway_port}" -Jthreads="${threads}" -Jramp=30 \
    -Jduration="${warmup_seconds}" -Jrate_per_minute="${rate_per_minute}" \
    -Jtoken_file="${token_file}" -l "${results_dir}/run-${run}-warmup.jtl" \
    -j "${results_dir}/run-${run}-warmup-jmeter.log"
  gzip -9 "${results_dir}/run-${run}-warmup.jtl"

  printf 'run=%s phase=measurement started=%s\n' "${run}" "$(date -u +%FT%TZ)"
  get_token
  monitor "${results_dir}/run-${run}-resource-samples.txt" &
  monitor_pid=$!
  "${jmeter}" -n -t "${project_root}/tests/performance/orders-load.jmx" \
    -Jhost=localhost -Jport="${gateway_port}" -Jthreads="${threads}" -Jramp=30 \
    -Jduration="${measure_seconds}" -Jrate_per_minute="${rate_per_minute}" \
    -Jtoken_file="${token_file}" -l "${results_dir}/run-${run}-measurement.jtl" \
    -j "${results_dir}/run-${run}-measurement-jmeter.log"
  kill "${monitor_pid}" 2>/dev/null || true
  wait "${monitor_pid}" 2>/dev/null || true
  monitor_pid=

  summarize_jtl "${run}" "${results_dir}/run-${run}-measurement.jtl" \
    "${results_dir}/run-${run}-summary.json"
  docker exec "${compose_project}-kafka-1" /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --all-groups \
    >"${results_dir}/run-${run}-kafka-groups.txt" 2>&1 || true
  capture_database "${results_dir}/run-${run}-database-after.txt"
  gzip -9 "${results_dir}/run-${run}-measurement.jtl"
  jq -c . "${results_dir}/run-${run}-summary.json"
done

capture_database "${results_dir}/database-after.txt"
python3 "${project_root}/tests/performance/summarize_jtl.py" aggregate \
  "${results_dir}"/run-*-summary.json >"${results_dir}/aggregate-summary.json"
printf 'completed_utc=%s\n' "$(date -u +%FT%TZ)" >>"${results_dir}/environment.txt"
jq . "${results_dir}/aggregate-summary.json"

jq -e ".aggregate.errors == 0" "${results_dir}/aggregate-summary.json" >/dev/null
