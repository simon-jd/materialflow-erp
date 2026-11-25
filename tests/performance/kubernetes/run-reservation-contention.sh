#!/usr/bin/env bash
# Runs ReservationContentionHarness in the load-generator pod against an existing, dedicated
# PostgreSQL database (for example `harness` on the AKS environment's Flexible Server; never the
# application database: the harness drops and recreates its own two tables). Extra arguments go
# to the harness, e.g. --requests=4000 --repetitions=3 --workers=8,32,64,128.
#
# Required: MATERIALFLOW_KUBE_CONTEXT, HARNESS_JDBC_URL (jdbc:postgresql://host:5432/harness?sslmode=require),
# HARNESS_JDBC_USER and HARNESS_PASSWORD_FILE (a local file holding only the password; it is
# streamed to the pod on stdin and never appears on a command line or in the results).
set -euo pipefail

results_dir=${1:?Usage: run-reservation-contention.sh NEW_RESULTS_DIRECTORY [--name=value ...]}
shift
context=${MATERIALFLOW_KUBE_CONTEXT:?Set MATERIALFLOW_KUBE_CONTEXT (named explicitly)}
jdbc_url=${HARNESS_JDBC_URL:?Set HARNESS_JDBC_URL}
jdbc_user=${HARNESS_JDBC_USER:?Set HARNESS_JDBC_USER}
password_file=${HARNESS_PASSWORD_FILE:?Set HARNESS_PASSWORD_FILE}
loadgen_namespace=${MATERIALFLOW_LOADGEN_NAMESPACE:-materialflow-loadgen}
loadgen_pod=${MATERIALFLOW_LOADGEN_POD:-loadgen}
work=/work/harness-$(date -u +%Y%m%dT%H%M%S)
sampler_pid=

database=${jdbc_url#jdbc:postgresql://*/}
database=${database%%\?*}
if [[ ${jdbc_url} != jdbc:postgresql://*/* || ${database} == materialflow || ${database} == keycloak \
      || ${database} == postgres ]]; then
  printf 'HARNESS_JDBC_URL must name a dedicated database, not %s\n' "${database}" >&2
  exit 1
fi
if [[ -e ${results_dir} ]]; then
  printf 'Refusing to overwrite existing results: %s\n' "${results_dir}" >&2
  exit 1
fi
mkdir -p "${results_dir}"

loadgen() { kubectl --context "${context}" -n "${loadgen_namespace}" exec -i "${loadgen_pod}" -c loadgen -- "$@"; }
# shellcheck disable=SC2329  # called by the EXIT trap
cleanup() {
  if [[ -n ${sampler_pid} ]]; then kill "${sampler_pid}" 2>/dev/null || true; fi
}
trap cleanup EXIT

# Node CPU (burstable nodes) while the harness runs.
sample() {
  while true; do
    printf '=== %s\n' "$(date -u +%FT%TZ)"
    kubectl --context "${context}" top nodes --no-headers 2>&1 || true
    kubectl --context "${context}" -n "${loadgen_namespace}" top pod "${loadgen_pod}" --no-headers 2>&1 || true
    sleep 30
  done
}

{
  printf 'started_utc=%s\narguments=%s\n' "$(date -u +%FT%TZ)" "$*"
  kubectl --context "${context}" get nodes -o wide
  kubectl --context "${context}" -n "${loadgen_namespace}" get pod "${loadgen_pod}" -o wide
  kubectl --context "${context}" -n "${loadgen_namespace}" get pod "${loadgen_pod}" \
    -o jsonpath='image={.status.containerStatuses[0].image} imageID={.status.containerStatuses[0].imageID}{"\n"}'
  loadgen sh -c 'uname -a; java -version 2>&1; nproc' </dev/null
} >"${results_dir}/environment.txt" 2>&1

sample >"${results_dir}/node-samples.txt" 2>&1 &
sampler_pid=$!

# The password arrives on stdin; the harness reads it from its environment.
loadgen sh -c "mkdir -p ${work} && cd ${work} || exit 1
  read -r password || [ -n \"\${password}\" ] || exit 1
  export MATERIALFLOW_HARNESS_JDBC_PASSWORD=\"\${password}\"
  unset password
  nohup sh -c 'java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
    -cp /opt/materialflow/materialflow-benchmarks.jar com.materialflow.performance.reservation.ReservationContentionHarness \
    --output-dir=${work}/out --jdbc-url=${jdbc_url} --jdbc-user=${jdbc_user} $* > harness.log 2>&1; echo \$? > exit-code' \
    </dev/null >/dev/null 2>&1 &" <"${password_file}"

until loadgen test -f "${work}/exit-code" </dev/null 2>/dev/null; do sleep 15; done
kill "${sampler_pid}" 2>/dev/null || true
sampler_pid=

exit_code=$(loadgen cat "${work}/exit-code" </dev/null)
loadgen cat "${work}/harness.log" </dev/null >"${results_dir}/harness.log"
for file in summary.json summary.csv runs.json; do
  loadgen cat "${work}/out/${file}" </dev/null >"${results_dir}/${file}" || true
done
loadgen rm -rf "${work}" </dev/null
printf 'completed_utc=%s exit_code=%s\n' "$(date -u +%FT%TZ)" "${exit_code}" >>"${results_dir}/environment.txt"
exit "${exit_code}"
