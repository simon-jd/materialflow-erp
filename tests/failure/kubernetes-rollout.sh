#!/usr/bin/env bash
set -euo pipefail

cluster=${MATERIALFLOW_KIND_CLUSTER:-materialflow}
namespace=${MATERIALFLOW_K8S_NAMESPACE:-materialflow}
context=kind-${cluster}
gateway_port=${MATERIALFLOW_K8S_GATEWAY_PORT:-18081}
forward_log=$(mktemp)
port_forward_pid=

cleanup() {
  if [[ -n ${port_forward_pid} ]]; then
    kill "$port_forward_pid" 2>/dev/null || true
    wait "$port_forward_pid" 2>/dev/null || true
  fi
  rm -f "$forward_log"
}
trap cleanup EXIT

kubectl --context "$context" -n "$namespace" port-forward \
  service/materialflow-edge-gateway "${gateway_port}:8081" >"$forward_log" 2>&1 &
port_forward_pid=$!

for _ in $(seq 1 30); do
  if curl -fsS "http://localhost:${gateway_port}/actuator/health" >/dev/null; then
    break
  fi
  sleep 1
done
curl -fsS "http://localhost:${gateway_port}/actuator/health" >/dev/null

order_body='{"customerId":"33333333-3333-3333-3333-333333333333","allowPartialFulfillment":true,"lines":[{"materialId":"55555555-5555-5555-5555-555555555555","warehouseId":"22222222-2222-2222-2222-222222222222","locationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1","quantity":0.001}]}'

create_order() {
  local phase=$1
  local code
  code=$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "http://localhost:${gateway_port}/api/v1/orders" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: k8s-${phase}-$(date +%s%N)" \
    -H 'X-Organization-Id: 11111111-1111-1111-1111-111111111111' \
    -H 'X-Actor-Id: 66666666-6666-6666-6666-666666666601' \
    -H 'X-Roles: SALES_ORDER_WRITE' \
    -H 'X-Warehouse-Scope: 22222222-2222-2222-2222-222222222222' \
    -d "$order_body")
  [[ $code == 201 ]]
}

create_order baseline

deleted_pod=$(kubectl --context "$context" -n "$namespace" get pods \
  -l app.kubernetes.io/name=materialflow-erp-core \
  -o jsonpath='{.items[0].metadata.name}')
kubectl --context "$context" -n "$namespace" delete pod "$deleted_pod" --wait=false >/dev/null
create_order pod-restart
kubectl --context "$context" -n "$namespace" rollout status \
  deployment/materialflow-erp-core --timeout=180s >/dev/null

rollout_value=$(date +%s%N)
kubectl --context "$context" -n "$namespace" patch deployment materialflow-erp-core \
  --type merge -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"materialflow.test/rollout\":\"${rollout_value}\"}}}}}" >/dev/null

rollout_requests=0
for index in $(seq 1 10); do
  create_order "rolling-${index}"
  rollout_requests=$((rollout_requests + 1))
done
kubectl --context "$context" -n "$namespace" rollout status \
  deployment/materialflow-erp-core --timeout=180s >/dev/null

unavailable=$(kubectl --context "$context" -n "$namespace" get deployment materialflow-erp-core \
  -o jsonpath='{.status.unavailableReplicas}')
[[ -z $unavailable || $unavailable == 0 ]]

printf 'pod_restart_recovered=true deleted_pod=%s rolling_update_recovered=true successful_during_rollout=%s unavailable=%s\n' \
  "$deleted_pod" "$rollout_requests" "${unavailable:-0}"
