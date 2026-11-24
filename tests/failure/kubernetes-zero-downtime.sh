#!/usr/bin/env bash
# Zero-downtime drill on a real cluster (AKS runbook, tests/failure/README.md): steady order
# traffic at a constant arrival rate from the load-generator pod through the gateway's in-cluster
# Service, one disruption while it runs, then failures and latency per window and an order count
# in PostgreSQL. Synthetic data only; each run seeds its own SKU with ample stock.
#
# Usage: kubernetes-zero-downtime.sh DISRUPTION NEW_OUTPUT_DIRECTORY
#   rollout-erp-core      kubectl rollout restart of erp-core
#   rollout-edge-gateway  kubectl rollout restart of the API gateway
#   delete-erp-core-pod   delete one erp-core pod
#   drain-erp-core-node   cordon and drain the node hosting erp-core, then uncordon it
#
# Required: MATERIALFLOW_KUBE_CONTEXT (named explicitly, never the current one) and
# MATERIALFLOW_PUBLIC_URL (Keycloak issuer for the synthetic token). The load-generator pod
# (tests/performance/kubernetes/loadgen-pod.yaml) must be running.
# Optional: ZDT_RATE orders/s (15), ZDT_BASELINE_SECONDS (90), ZDT_TAIL_SECONDS (90),
# ZDT_TARGET (gateway Service), MATERIALFLOW_NAMESPACE, MATERIALFLOW_LOADGEN_NAMESPACE,
# MATERIALFLOW_LOADGEN_POD.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
disruption=${1:?Usage: kubernetes-zero-downtime.sh DISRUPTION NEW_OUTPUT_DIRECTORY}
out=${2:?Usage: kubernetes-zero-downtime.sh DISRUPTION NEW_OUTPUT_DIRECTORY}
context=${MATERIALFLOW_KUBE_CONTEXT:?Set MATERIALFLOW_KUBE_CONTEXT to the cluster context}
public_url=${MATERIALFLOW_PUBLIC_URL:?Set MATERIALFLOW_PUBLIC_URL, e.g. https://materialflow-erp.westus.cloudapp.azure.com}
ns=${MATERIALFLOW_NAMESPACE:-materialflow}
lg_ns=${MATERIALFLOW_LOADGEN_NAMESPACE:-materialflow-loadgen}
lg_pod=${MATERIALFLOW_LOADGEN_POD:-loadgen}
rate=${ZDT_RATE:-15}
baseline=${ZDT_BASELINE_SECONDS:-90}
tail_seconds=${ZDT_TAIL_SECONDS:-90}
target=${ZDT_TARGET:-http://materialflow-edge-gateway.${ns}.svc.cluster.local:8081}
customer_prefix=5eed0000-0000-4000-8000-
customers=50
run_id="zdt-$(date -u +%Y%m%dT%H%M%S)-${disruption}"
work=/work/${run_id}

case ${disruption} in
  rollout-erp-core|rollout-edge-gateway|delete-erp-core-pod|drain-erp-core-node) ;;
  *) printf 'Unknown disruption %s\n' "${disruption}" >&2; exit 1 ;;
esac
if [[ -e ${out} ]]; then
  printf 'Refusing to overwrite existing evidence: %s\n' "${out}" >&2
  exit 1
fi

k() { kubectl --context "${context}" -n "${ns}" "$@"; }
lg() { kubectl --context "${context}" -n "${lg_ns}" exec "${lg_pod}" -c loadgen -- "$@"; }
lg_in() { kubectl --context "${context}" -n "${lg_ns}" exec -i "${lg_pod}" -c loadgen -- "$@"; }
psql() { k exec -i deploy/materialflow-toolbox -c psql -- psql -v ON_ERROR_STOP=1 -qAt "$@"; }
now_ms() { date +%s%3N; }
stamp() { printf '%s %s %s\n' "$(now_ms)" "$(date -u +%FT%T.%3NZ)" "$*" >>"${out}/timeline.txt"; }

[[ $(kubectl --context "${context}" -n "${lg_ns}" get pod "${lg_pod}" -o jsonpath='{.status.phase}') == Running ]]
mkdir -p "${out}/logs"
sampler_pid=
pg_sampler_pid=
log_pids=()
stop_samplers() {
  for pid in ${sampler_pid} ${pg_sampler_pid} "${log_pids[@]}"; do kill "${pid}" 2>/dev/null || true; done
  sampler_pid='' pg_sampler_pid='' log_pids=()
  psql -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = 'mf_zdt_sampler'" \
    >/dev/null 2>&1 </dev/null || true
}
cordoned_node=
cleanup() {
  # Never leave a node cordoned, whatever stopped the drill.
  if [[ -n ${cordoned_node} ]]; then kubectl --context "${context}" uncordon "${cordoned_node}" || true; fi
  stop_samplers
  lg sh -c "test -f ${work}/k6.pid && kill -INT \$(cat ${work}/k6.pid) 2>/dev/null; rm -f ${work}/token" 2>/dev/null || true
}
trap cleanup EXIT

# Pod and node state every 10 s while the drill runs.
sample() {
  while true; do
    printf '=== %s\n' "$(date -u +%FT%TZ)"
    kubectl --context "${context}" top nodes --no-headers 2>&1 || true
    k get pods -o wide --no-headers 2>&1 | awk '{print $1, $2, $3, $4, $7}' || true
    sleep 10
  done
}

# Database view once per second: sessions waiting on a lock, idle in transaction, the longest open
# transaction and who blocks the waiters (pid:client node:state:wait event:transaction age ms).
pg_sample() {
  printf '%s\n' "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'HH24:MI:SS.MS') AS t,
    count(*) FILTER (WHERE wait_event_type = 'Lock') AS lock_waiters,
    count(*) FILTER (WHERE state = 'idle in transaction') AS idle_in_tx,
    count(*) FILTER (WHERE state = 'active') AS active,
    coalesce(round(extract(epoch FROM max(clock_timestamp() - xact_start)) * 1000), 0) AS longest_xact_ms,
    (SELECT string_agg(DISTINCT b.pid || ':' || coalesce(host(b.client_addr), '-') || ':' || b.state || ':'
        || coalesce(b.wait_event, '-') || ':' || round(extract(epoch FROM clock_timestamp() - b.xact_start) * 1000), ' ')
       FROM pg_stat_activity w CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) bp
       JOIN pg_stat_activity b ON b.pid = bp WHERE w.datname = 'materialflow') AS blockers
  FROM pg_stat_activity WHERE datname = 'materialflow' AND pid <> pg_backend_pid() \\watch i=1 c=3600" \
    | k exec -i deploy/materialflow-toolbox -c psql -- env PGAPPNAME=mf_zdt_sampler psql -XAt -F ' '
}

{
  printf 'run_id=%s disruption=%s rate=%s baseline_s=%s tail_s=%s target=%s\n' \
    "${run_id}" "${disruption}" "${rate}" "${baseline}" "${tail_seconds}" "${target}"
  kubectl --context "${context}" get nodes -o wide
  k get deploy,pdb -o wide
  k get pods -o wide
  for d in erp-core edge-gateway; do
    k get deploy "materialflow-${d}" -o jsonpath="{.metadata.name} strategy={.spec.strategy.rollingUpdate} grace={.spec.template.spec.terminationGracePeriodSeconds} preStop={.spec.template.spec.containers[0].lifecycle.preStop} readiness={.spec.template.spec.containers[0].readinessProbe}{'\n'}"
  done
  lg k6 version
} >"${out}/environment.txt" 2>&1

# Fresh SKU with stock for far more orders than the run sends, and synthetic customers.
material=$(python3 -c 'import uuid; print(uuid.uuid4())')
location=$(python3 -c 'import uuid; print(uuid.uuid4())')
psql -v material="${material}" -v location="${location}" -v sku="ZDT-${run_id}" -v prefix="${customer_prefix}" \
  -v customers="${customers}" <<'SQL'
INSERT INTO materials (id, organization_id, sku, name, base_unit)
VALUES (:'material', '11111111-1111-1111-1111-111111111111', :'sku', 'Synthetic zero-downtime SKU', 'PIECE');
INSERT INTO price_rules (id, organization_id, source, material_id, unit_price, discount_rate, rule_version, valid_from)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'STANDARD', :'material', 10.0000, 0, 1,
        TIMESTAMPTZ '2026-01-01T00:00:00Z');
INSERT INTO inventory_balances (id, organization_id, warehouse_id, material_id, location_id, on_hand)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
        :'material', :'location', 10000000);
INSERT INTO customers (id, organization_id, code, name, region_code)
SELECT (:'prefix' || lpad(n::text, 12, '0'))::uuid, '11111111-1111-1111-1111-111111111111',
       'ZDT-CUST-' || n, 'Synthetic zero-downtime customer ' || n, 'WEST'
  FROM generate_series(0, :customers - 1) n
ON CONFLICT DO NOTHING;
INSERT INTO credit_accounts (id, organization_id, customer_id, credit_limit)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', c.id, 100000000.00
  FROM customers c WHERE c.code LIKE 'ZDT-CUST-%'
ON CONFLICT DO NOTHING;
SQL

lg mkdir -p "${work}"
curl -fsS "${public_url}/idp/realms/materialflow/protocol/openid-connect/token" \
  --data-urlencode client_id=materialflow-cli --data-urlencode grant_type=password \
  --data-urlencode username=synthetic-demo --data-urlencode password=synthetic-demo-only \
  | jq -er .access_token | lg_in sh -c "umask 077; cat > ${work}/token"
lg_in sh -c "cat > ${work}/orders.js" <"${project_root}/tests/performance/kubernetes/orders-constant-rate.js"

# Clock offset of the load generator against this machine (k6 stamps requests with the pod's clock).
# It is applied only when it exceeds the measurement's own uncertainty (half the exec round trip).
before=$(now_ms); pod_ms=$(lg date +%s%3N); after=$(now_ms)
offset=$(( pod_ms - (before + after) / 2 ))
uncertainty=$(( (after - before) / 2 ))
if (( ${offset#-} <= uncertainty )); then applied=0; else applied=${offset}; fi
printf '{"local_before_ms":%s,"pod_ms":%s,"local_after_ms":%s,"estimated_offset_ms":%s,"uncertainty_ms":%s,"applied_offset_ms":%s}\n' \
  "${before}" "${pod_ms}" "${after}" "${offset}" "${uncertainty}" "${applied}" >"${out}/clock.json"
offset=${applied}

sample >"${out}/samples.txt" 2>&1 &
sampler_pid=$!
pg_sample >"${out}/pg-samples.txt" 2>&1 &
pg_sampler_pid=$!
stamp load-start
load_started=$(now_ms)
lg sh -c "cd ${work} || exit 1; nohup k6 run --quiet --no-color --log-format=raw --console-output=${work}/requests.jsonl \
  -e BASE_URL=${target} -e TOKEN_FILE=${work}/token -e MATERIAL_ID=${material} -e LOCATION_ID=${location} \
  -e CUSTOMER_PREFIX=${customer_prefix} -e CUSTOMERS=${customers} -e RUN_ID=${run_id} -e RATE=${rate} \
  -e DURATION=30m -e SUMMARY_FILE=${work}/k6-summary.json orders.js >${work}/k6.out 2>&1 & echo \$! > ${work}/k6.pid"
sleep "${baseline}"

# Logs of the pods that the disruption will replace, followed until they exit.
for pod in $(k get pods -l 'app.kubernetes.io/name in (materialflow-erp-core,materialflow-edge-gateway)' -o name); do
  k logs -f "${pod}" --since=1s >"${out}/logs/${pod#pod/}.log" 2>&1 &
  log_pids+=($!)
done
started=$(now_ms)
stamp "disruption-start ${disruption}"
case ${disruption} in
  rollout-erp-core|rollout-edge-gateway)
    deployment=materialflow-${disruption#rollout-}
    k rollout restart "deploy/${deployment}"
    k rollout status "deploy/${deployment}" --timeout=15m
    ;;
  delete-erp-core-pod)
    pod=$(k get pods -l app.kubernetes.io/name=materialflow-erp-core -o jsonpath='{.items[0].metadata.name}')
    stamp "deleting ${pod}"
    k delete pod "${pod}"
    stamp "deleted ${pod}"
    k rollout status deploy/materialflow-erp-core --timeout=15m
    ;;
  drain-erp-core-node)
    node=$(k get pods -l app.kubernetes.io/name=materialflow-erp-core -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' \
      | sort | uniq -c | sort -rn | awk 'NR==1 {print $2}')
    stamp "draining ${node} (erp-core pods: $(k get pods -l app.kubernetes.io/name=materialflow-erp-core -o jsonpath='{.items[*].spec.nodeName}'))"
    cordoned_node=${node}
    # A drain that cannot finish (for example a PDB whose replacement pod cannot be scheduled) is a
    # result, not a reason to stop: record it, uncordon, and measure the recovery.
    if kubectl --context "${context}" drain "${node}" --ignore-daemonsets --delete-emptydir-data \
        --timeout="${ZDT_DRAIN_TIMEOUT:-5m}" >"${out}/drain.txt" 2>&1; then
      stamp "drained ${node}"
    else
      stamp "drain of ${node} did not complete within ${ZDT_DRAIN_TIMEOUT:-5m}"
    fi
    k rollout status deploy/materialflow-erp-core --timeout=3m || stamp 'erp-core not fully available while cordoned'
    stamp "erp-core rollout status returned while ${node} cordoned"
    kubectl --context "${context}" get pods -A -o wide >"${out}/pods-while-cordoned.txt" 2>&1 || true
    kubectl --context "${context}" uncordon "${node}"
    cordoned_node=
    stamp "uncordoned ${node}"
    for deployment in $(k get deploy -o name); do
      k rollout status "${deployment}" --timeout=10m || stamp "${deployment} not Ready after uncordon"
    done
    k rollout status statefulset/kafka --timeout=10m || stamp 'kafka not Ready after uncordon'
    ;;
esac
finished=$(now_ms)
stamp "disruption-finished ${disruption}"
k get events --sort-by=.lastTimestamp >"${out}/events.txt" 2>&1 || true
sleep "${tail_seconds}"
stamp load-stop
lg sh -c "kill -INT \$(cat ${work}/k6.pid)"
for _ in $(seq 1 60); do lg test -s "${work}/k6-summary.json" && break; sleep 1; done
stop_samplers
# Logs of the pods that exist now (replacements included), from the load start.
for pod in $(k get pods -l 'app.kubernetes.io/name in (materialflow-erp-core,materialflow-edge-gateway)' -o name); do
  [[ -s ${out}/logs/${pod#pod/}.log ]] || k logs "${pod}" --since-time="$(date -u -d "@$(( load_started / 1000 ))" +%FT%TZ)" \
    >"${out}/logs/${pod#pod/}.log" 2>&1 || true
done
gzip -9 "${out}"/logs/*.log

lg cat "${work}/requests.jsonl" >"${out}/requests.jsonl"
lg cat "${work}/k6-summary.json" >"${out}/k6-summary.json"
lg cat "${work}/k6.out" >"${out}/k6.out" || true
lg rm -rf "${work}"
k get events --sort-by=.lastTimestamp >"${out}/events-end.txt" 2>&1 || true
kubectl --context "${context}" get events -A --sort-by=.lastTimestamp >"${out}/events-all-namespaces.txt" 2>&1 || true
k get pods -o wide >"${out}/pods-after.txt"

# Disruption boundaries in the load generator's clock.
printf '{"disruption":"%s","run_id":"%s","rate":%s,"clock_offset_ms":%s,"disruption_started_ms":%s,"disruption_finished_ms":%s}\n' \
  "${disruption}" "${run_id}" "${rate}" "${offset}" "$(( started + offset ))" "$(( finished + offset ))" >"${out}/events.json"
python3 "${script_dir}/zero_downtime.py" "${out}/requests.jsonl" "${out}/events.json" >"${out}/analysis.json"
# k6's own counters: iterations it could not start at the configured rate, and the VUs it needed.
jq --slurpfile k6 "${out}/k6-summary.json" '. + {k6: {
    dropped_iterations: ($k6[0].metrics.dropped_iterations.values.count // 0),
    iterations: $k6[0].metrics.iterations.values.count, vus_max: $k6[0].metrics.vus_max.values.max}}' \
  "${out}/analysis.json" >"${out}/summary.json"
rm "${out}/analysis.json"

# Every acknowledged order exists exactly once; requests that failed at the client may or may not
# have created one (an idempotent retry with the same key would return it).
psql -v material="${material}" -v run_id="${run_id}" >"${out}/orders.json" <<'SQL'
SELECT json_build_object(
  'orders', (SELECT count(DISTINCT o.id) FROM sales_orders o JOIN sales_order_lines l ON l.order_id = o.id
              WHERE l.material_id = :'material'),
  'completed_idempotency_keys', (SELECT count(*) FROM idempotency_records
              WHERE idempotency_key LIKE :'run_id' || '-%' AND status = 'COMPLETED'),
  'reserved', (SELECT reserved FROM inventory_balances WHERE material_id = :'material'))
SQL
acknowledged=$(jq '.total.status_counts["201"] // 0' "${out}/summary.json")
jq --argjson acknowledged "${acknowledged}" '. + {acknowledged_201: $acknowledged}' "${out}/orders.json" >"${out}/orders.tmp" \
  && mv "${out}/orders.tmp" "${out}/orders.json"
gzip -9 "${out}/requests.jsonl"
jq -c '{disruption, duration_ms: .disruption_duration_ms, total: .total | {requests, failed, status_counts},
  steady: .windows.steady | {requests, failed, p95_ms, p99_ms},
  disruption_window: .windows.disruption | {requests, failed, non_2xx, no_response, p95_ms, p99_ms, max_ms},
  after: .windows.after | {requests, failed, p95_ms, p99_ms}, time_to_steady_ms, time_to_steady_after_finish_ms, k6}' \
  "${out}/summary.json"
jq -c . "${out}/orders.json"
jq -e '.orders >= .acknowledged_201 and .orders == .completed_idempotency_keys' "${out}/orders.json" >/dev/null
