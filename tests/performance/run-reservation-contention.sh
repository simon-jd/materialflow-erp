#!/usr/bin/env bash
# Builds and runs the reservation strategy comparison on a disposable PostgreSQL 17.11 container.
# Extra arguments are passed to the harness, e.g. --requests=4000 --repetitions=3 --workers=8,32,64,128.
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
results_dir=${1:?Usage: run-reservation-contention.sh NEW_RESULTS_DIRECTORY [--name=value ...]}
shift
java=${JAVA_HOME:+${JAVA_HOME}/bin/}java
sampler_pid=

cleanup() {
  if [[ -n ${sampler_pid} ]]; then
    kill "${sampler_pid}" 2>/dev/null || true
    wait "${sampler_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ -e ${results_dir} ]]; then
  printf 'Refusing to overwrite existing results: %s\n' "${results_dir}" >&2
  exit 1
fi
mkdir -p "${results_dir}"

(cd "${project_root}" && mvn -B -ntp -q -pl tests/performance -am package -DskipTests)

{
  printf 'started_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'arguments=%s\n' "$*"
  uname -a
  "${java}" -version 2>&1
  docker version --format 'docker server={{.Server.Version}} client={{.Client.Version}}'
  docker info --format 'docker cpus={{.NCPU}} memory_bytes={{.MemTotal}}'
} >"${results_dir}/environment.txt"

# Everything else on the Docker host competes for the same CPUs, so record it while measuring.
sample_host() {
  while true; do
    printf 'timestamp=%s load=%s\n' "$(date -u +%FT%TZ)" "$(uptime | sed 's/.*load averages*: //')"
    docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
    sleep 30
  done
}
sample_host >"${results_dir}/host-samples.txt" 2>&1 &
sampler_pid=$!

"${java}" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -cp "${project_root}/tests/performance/target/materialflow-benchmarks.jar" \
  com.materialflow.performance.reservation.ReservationContentionHarness \
  --output-dir="${results_dir}" "$@" | tee "${results_dir}/harness.log"

printf 'completed_utc=%s\n' "$(date -u +%FT%TZ)" >>"${results_dir}/environment.txt"
