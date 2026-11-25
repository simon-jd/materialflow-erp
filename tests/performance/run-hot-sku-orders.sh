#!/usr/bin/env bash
# Hot-SKU order contention through the HTTP API of a running qualification stack. Each run seeds a
# fresh synthetic SKU whose stock covers only part of the demand, fires waves of concurrent order
# submissions with JMeter (some reusing an in-flight Idempotency-Key), then verifies the database.
#
# MATERIALFLOW_HOT_SKU_BACKEND=compose (default): JMeter from JMETER_HOME on this machine against a
# local qualification Compose project. =kubernetes: JMeter runs in the load-generator pod
# (tests/performance/kubernetes/) against the in-cluster Services, PostgreSQL is reached through the
# psql toolbox pod; needs MATERIALFLOW_KUBE_CONTEXT and MATERIALFLOW_PUBLIC_URL (token issuer).
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
results_dir=${1:?Usage: run-hot-sku-orders.sh NEW_RESULTS_DIRECTORY}
backend=${MATERIALFLOW_HOT_SKU_BACKEND:-compose}
target=${MATERIALFLOW_HOT_SKU_TARGET:-gateway}
case ${backend} in
  compose)
    jmeter=${JMETER_HOME:?Set JMETER_HOME to an Apache JMeter 5.6.3 installation}/bin/jmeter
    compose_project=${COMPOSE_PROJECT_NAME:?Set COMPOSE_PROJECT_NAME to the qualification project}
    target_host=localhost
    case ${target} in
      gateway) target_port=${MATERIALFLOW_GATEWAY_PORT:-28081} ;;
      core) target_port=${MATERIALFLOW_ERP_PORT:-28080} ;;
      *) printf 'MATERIALFLOW_HOT_SKU_TARGET must be gateway or core\n' >&2; exit 1 ;;
    esac
    keycloak_port=${MATERIALFLOW_KEYCLOAK_PORT:-28180}
    token_url="http://localhost:${keycloak_port}/realms/materialflow/protocol/openid-connect/token"
    postgres="${compose_project}-postgres-1"
    ;;
  kubernetes)
    context=${MATERIALFLOW_KUBE_CONTEXT:?Set MATERIALFLOW_KUBE_CONTEXT (named explicitly, never the current one)}
    public_url=${MATERIALFLOW_PUBLIC_URL:?Set MATERIALFLOW_PUBLIC_URL to the environment URL}
    namespace=${MATERIALFLOW_NAMESPACE:-materialflow}
    loadgen_namespace=${MATERIALFLOW_LOADGEN_NAMESPACE:-materialflow-loadgen}
    loadgen_pod=${MATERIALFLOW_LOADGEN_POD:-loadgen}
    case ${target} in
      gateway) target_host=materialflow-edge-gateway.${namespace}.svc.cluster.local; target_port=8081 ;;
      core) target_host=materialflow-erp-core.${namespace}.svc.cluster.local; target_port=8080 ;;
      *) printf 'MATERIALFLOW_HOT_SKU_TARGET must be gateway or core\n' >&2; exit 1 ;;
    esac
    token_url="${public_url}/idp/realms/materialflow/protocol/openid-connect/token"
    pod_work=/work/hot-sku-$$
    ;;
  *) printf 'MATERIALFLOW_HOT_SKU_BACKEND must be compose or kubernetes\n' >&2; exit 1 ;;
esac
runs=${MATERIALFLOW_HOT_SKU_RUNS:-3}
warmup_runs=${MATERIALFLOW_HOT_SKU_WARMUP_RUNS:-1}
threads=${MATERIALFLOW_HOT_SKU_THREADS:-100}
waves=${MATERIALFLOW_HOT_SKU_WAVES:-5}
duplicates=${MATERIALFLOW_HOT_SKU_DUPLICATES:-10}
customers=${MATERIALFLOW_HOT_SKU_CUSTOMERS:-50}
quantity=${MATERIALFLOW_HOT_SKU_QUANTITY:-7}
stock=${MATERIALFLOW_HOT_SKU_STOCK:-1000}
# The gateway limits each principal to 50 requests/s with a burst of 100; a 2 s pause refills the bucket.
pause_ms=${MATERIALFLOW_HOT_SKU_PAUSE_MS:-2000}
customer_prefix=c0ffee00-0000-4000-8000-
token_file=$(mktemp)
sampler_pid=

kube() { kubectl --context "${context}" -n "${namespace}" "$@"; }
loadgen() { kubectl --context "${context}" -n "${loadgen_namespace}" exec -i "${loadgen_pod}" -c loadgen -- "$@"; }

cleanup() {
  if [[ -n ${sampler_pid} ]]; then
    kill "${sampler_pid}" 2>/dev/null || true
    wait "${sampler_pid}" 2>/dev/null || true
  fi
  rm -f "${token_file}"
  if [[ ${backend} == kubernetes ]]; then
    loadgen rm -rf "${pod_work}" </dev/null 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ ${backend} == compose && ! -x ${jmeter} ]]; then
  printf 'JMeter is not executable at %s\n' "${jmeter}" >&2
  exit 1
fi
if (( duplicates * 2 > threads )); then
  printf 'Duplicates per wave must not exceed half of the threads\n' >&2
  exit 1
fi
if [[ -e ${results_dir} ]]; then
  printf 'Refusing to overwrite existing evidence: %s\n' "${results_dir}" >&2
  exit 1
fi
mkdir -p "${results_dir}"
chmod 600 "${token_file}"

psql() {
  if [[ ${backend} == kubernetes ]]; then
    kube exec -i deploy/materialflow-toolbox -c psql -- psql -v ON_ERROR_STOP=1 -qAt "$@"
  else
    docker exec -i "${postgres}" psql -U materialflow -d materialflow -v ON_ERROR_STOP=1 -qAt "$@"
  fi
}

get_token() {
  local attempt response
  for attempt in $(seq 1 30); do
    if response=$(curl -fsS "${token_url}" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'client_id=materialflow-cli' \
        --data-urlencode 'grant_type=password' \
        --data-urlencode 'username=synthetic-demo' \
        --data-urlencode 'password=synthetic-demo-only'); then
      printf '%s' "${response}" | jq -er '.access_token' >"${token_file}"
      if [[ ${backend} == kubernetes ]]; then
        loadgen sh -c "umask 077; cat > ${pod_work}/token" <"${token_file}"
      fi
      return
    fi
    sleep 2
  done
  printf 'Unable to obtain the synthetic token\n' >&2
  exit 1
}

# Customers are shared by all runs; each run gets its own SKU, so its checks are isolated.
seed_customers() {
  psql -v prefix="${customer_prefix}" -v customers="${customers}" <<'SQL'
INSERT INTO customers (id, organization_id, code, name, region_code)
SELECT (:'prefix' || lpad(n::text, 12, '0'))::uuid, '11111111-1111-1111-1111-111111111111',
       'HOT-SKU-CUST-' || n, 'Synthetic hot-SKU customer ' || n, 'WEST'
  FROM generate_series(0, :customers - 1) n
ON CONFLICT DO NOTHING;
INSERT INTO credit_accounts (id, organization_id, customer_id, credit_limit)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', c.id, 100000000.00
  FROM customers c
 WHERE c.code LIKE 'HOT-SKU-CUST-%'
ON CONFLICT DO NOTHING;
SQL
}

seed_sku() {
  psql -v material="$1" -v location="$2" -v sku="$3" -v stock="${stock}" <<'SQL'
INSERT INTO materials (id, organization_id, sku, name, base_unit)
VALUES (:'material', '11111111-1111-1111-1111-111111111111', :'sku', 'Synthetic hot SKU ' || :'sku', 'PIECE');
INSERT INTO price_rules
    (id, organization_id, source, material_id, unit_price, discount_rate, rule_version, valid_from)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'STANDARD', :'material', 10.0000, 0, 1,
        TIMESTAMPTZ '2026-01-01T00:00:00Z');
INSERT INTO inventory_balances (id, organization_id, warehouse_id, material_id, location_id, on_hand)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
        :'material', :'location', :stock);
SQL
}

sample_host() {
  while true; do
    printf 'timestamp=%s load=%s\n' "$(date -u +%FT%TZ)" "$(uptime | sed 's/.*load averages*: //')"
    if [[ ${backend} == kubernetes ]]; then
      # Node CPU against the burstable baseline, and the pods on the request path.
      kubectl --context "${context}" top nodes --no-headers 2>&1 || true
      kube top pods --no-headers -l 'app.kubernetes.io/name in (materialflow-erp-core,materialflow-edge-gateway)' 2>&1 || true
    else
      docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
      docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}}' \
        "${compose_project}-edge-gateway-1" "${compose_project}-erp-core-1" "${postgres}" 2>&1 || true
    fi
    psql -c "SELECT 'lock_waiters=' || count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" 2>&1 || true
    sleep 5
  done
}

# JMeter on this machine (compose) or in the load-generator pod (kubernetes); $1 = run name.
run_jmeter() {
  local name=$1
  shift
  if [[ ${backend} == kubernetes ]]; then
    loadgen jmeter -n -t "${pod_work}/hot-sku-orders.jmx" -Jtoken_file="${pod_work}/token" \
      -l "${pod_work}/${name}.jtl" -j "${pod_work}/${name}-jmeter.log" "$@" </dev/null >/dev/null 2>&1
    loadgen cat "${pod_work}/${name}.jtl" </dev/null >"${results_dir}/${name}.jtl"
    loadgen cat "${pod_work}/${name}-jmeter.log" </dev/null >"${results_dir}/${name}-jmeter.log"
  else
    "${jmeter}" -n -t "${project_root}/tests/performance/hot-sku-orders.jmx" -Jtoken_file="${token_file}" \
      -l "${results_dir}/${name}.jtl" -j "${results_dir}/${name}-jmeter.log" "$@" >/dev/null 2>&1
  fi
}

if [[ ${backend} == kubernetes ]]; then
  loadgen mkdir -p "${pod_work}" </dev/null
  loadgen sh -c "cat > ${pod_work}/hot-sku-orders.jmx" <"${project_root}/tests/performance/hot-sku-orders.jmx"
fi
{
  printf 'started_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'backend=%s target=%s host=%s port=%s warmup_runs=%s runs=%s threads=%s waves=%s duplicates=%s customers=%s quantity=%s stock=%s pause_ms=%s\n' \
    "${backend}" "${target}" "${target_host}" "${target_port}" "${warmup_runs}" "${runs}" "${threads}" "${waves}" \
    "${duplicates}" "${customers}" "${quantity}" "${stock}" "${pause_ms}"
  if [[ ${backend} == kubernetes ]]; then
    kubectl --context "${context}" get nodes -o wide
    kube get pods -o wide
    # shellcheck disable=SC2016  # expanded in the pod
    loadgen sh -c 'uname -a; java -version 2>&1; printf "jmeter=%s\n" "$(jmeter --version 2>&1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+$" | head -1)"' </dev/null
    kubectl --context "${context}" -n "${loadgen_namespace}" get pod "${loadgen_pod}" -o wide
  else
    uname -a
    java -version 2>&1
    printf 'jmeter=%s\n' "$("${jmeter}" --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+$' | head -1)"
    docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
    docker ps --filter "label=com.docker.compose.project=${compose_project}" --format '{{.Names}} {{.Image}} {{.ID}}'
  fi
} >"${results_dir}/environment.txt"
seed_customers

# One run: fresh SKU and token, JMeter, JTL summary, SQL invariants. Files are prefixed with $1.
run_once() {
  local name=$1 material location key_prefix
  material=$(python3 -c 'import uuid; print(uuid.uuid4())')
  location=$(python3 -c 'import uuid; print(uuid.uuid4())')
  key_prefix="hot-sku-$(date -u +%Y%m%dT%H%M%S)-${name}"
  seed_sku "${material}" "${location}" "HOT-${key_prefix}"
  get_token
  printf '%s material=%s key_prefix=%s started=%s\n' "${name}" "${material}" "${key_prefix}" "$(date -u +%FT%TZ)"

  sample_host >"${results_dir}/${name}-host-samples.txt" 2>&1 &
  sampler_pid=$!
  run_jmeter "${name}" -Jhost="${target_host}" -Jport="${target_port}" -Jthreads="${threads}" -Jwaves="${waves}" \
    -Jduplicates="${duplicates}" -Jcustomers="${customers}" -Jcustomer_prefix="${customer_prefix}" \
    -Jquantity="${quantity}" -Jmaterial_id="${material}" -Jlocation_id="${location}" \
    -Jkey_prefix="${key_prefix}" -Jpause_ms="${pause_ms}"
  kill "${sampler_pid}" 2>/dev/null || true
  wait "${sampler_pid}" 2>/dev/null || true
  sampler_pid=

  python3 "${project_root}/tests/performance/summarize_hot_sku.py" "${name##*-}" "${results_dir}/${name}.jtl" \
    >"${results_dir}/${name}-summary.json"
  psql -v material_id="${material}" -v key_prefix="${key_prefix}" -v expected_orders="${expected_orders}" \
    <"${project_root}/tests/performance/hot-sku-invariants.sql" | jq . >"${results_dir}/${name}-invariants.json"
  gzip -9 "${results_dir}/${name}.jtl"
  jq -c '{samples, errors, status_codes, p50_ms, p95_ms, p99_ms, throughput_rps, burst_throughput_rps}' \
    "${results_dir}/${name}-summary.json"
  jq -c '{holds, metrics: .metrics | {on_hand, reserved, orders, status_counts}}' \
    "${results_dir}/${name}-invariants.json"
}

expected_orders=$(( (threads - duplicates) * waves ))
# Warm-up runs absorb JIT, connection-pool and Redis client start-up; they are kept but not gated.
for (( warmup = 1; warmup <= warmup_runs; warmup++ )); do
  run_once "warmup-${warmup}"
done
for (( run = 1; run <= runs; run++ )); do
  run_once "run-${run}"
done

python3 "${project_root}/tests/performance/summarize_jtl.py" aggregate \
  "${results_dir}"/run-*-summary.json >"${results_dir}/aggregate-summary.json"
jq -s '{runs: length, all_hold: all(.holds), results: .}' "${results_dir}"/run-*-invariants.json \
  >"${results_dir}/invariants.json"
printf 'completed_utc=%s\n' "$(date -u +%FT%TZ)" >>"${results_dir}/environment.txt"

jq -e '.aggregate.errors == 0' "${results_dir}/aggregate-summary.json" >/dev/null
jq -e '.all_hold' "${results_dir}/invariants.json" >/dev/null
