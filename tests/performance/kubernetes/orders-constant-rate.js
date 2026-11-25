// Steady order traffic at a constant arrival rate (open model) for the zero-downtime drill
// (tests/failure/kubernetes-zero-downtime.sh). Every iteration POSTs one synthetic order with a
// unique Idempotency-Key and writes one JSON line per request to the console output:
//   {"i": iteration, "t": start epoch ms, "ms": wall-clock ms, "s": HTTP status (0 = no response),
//    "e": k6 error code or 0, "vu": VU}
// k6 starts more VUs when responses slow down, so a stall shows up as latency or errors instead
// of silently lowering the offered load; iterations it cannot start are counted in
// `dropped_iterations` (summary file).
//
// Environment: BASE_URL, TOKEN_FILE, MATERIAL_ID, LOCATION_ID, CUSTOMER_PREFIX, CUSTOMERS,
// RUN_ID, RATE (orders/s), DURATION (k6 duration), SUMMARY_FILE, optional PRE_VUS, MAX_VUS,
// TIMEOUT.
import http from 'k6/http';
import exec from 'k6/execution';

const token = open(__ENV.TOKEN_FILE).trim();
const customers = parseInt(__ENV.CUSTOMERS || '50', 10);
const warehouseId = '22222222-2222-2222-2222-222222222222';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE || '15', 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || '40', 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '400', 10),
    },
  },
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const customer = __ENV.CUSTOMER_PREFIX + String(iteration % customers).padStart(12, '0');
  const body = JSON.stringify({
    customerId: customer,
    allowPartialFulfillment: true,
    lines: [{materialId: __ENV.MATERIAL_ID, warehouseId, locationId: __ENV.LOCATION_ID, quantity: 1}],
  });
  const started = Date.now();
  const response = http.post(`${__ENV.BASE_URL}/api/v1/orders`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `${__ENV.RUN_ID}-${iteration}`,
      Authorization: `Bearer ${token}`,
    },
    timeout: __ENV.TIMEOUT || '10s',
    tags: {name: 'POST /api/v1/orders'},
  });
  console.log(JSON.stringify({
    i: iteration, t: started, ms: Date.now() - started, s: response.status, e: response.error_code || 0,
    vu: exec.vu.idInTest,
  }));
}

export function handleSummary(data) {
  return {[__ENV.SUMMARY_FILE]: JSON.stringify(data)};
}
