// S2a hot-SKU performance
// Question: how much slower is ordering when every request competes for the same inventory row? Where is the bottleneck?
// Load: stepped arrival rate STAGES (default 20,50,100,200 orders/s), STEP per step (default 1m), 10s ramp between steps
//   hot group:     -e HOT=1      -> only SKU 90101 (a single inventory row)
//   control group: -e HOT=0      -> random among 901's 10 SKUs
// Watch: per-step QPS / P99 / dropped_iterations; Hikari pending and Tomcat busy in Grafana; row lock waits from sample.sh
// Expected: find the knee (the step where QPS stops growing, P99 jumps and iterations start dropping); hot P99 clearly above control; check.sh passes
// Prerequisite: raise 901's rate limit（ORDER_OPTS='--merchanthub.order.sentinel.rate-limit.tenant-overrides[901]=100000'）
import exec from 'k6/execution';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, pick, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

const HOT = (__ENV.HOT || '1') === '1';
const STAGES = (__ENV.STAGES || '20,50,100,200').split(',').map(Number);
const STEP = __ENV.STEP || '1m';
const SKUS = HOT ? [config.bigSkus[0]] : config.bigSkus;

// Tag each step's hold phase with stage (r20 / r50 ...) and transitions with ramp, so the summary shows P50 / P99 per step (which step is the knee)
const STEP_S = parseInt(STEP, 10) * (STEP.endsWith('m') ? 60 : 1);
function stageTag() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const i = Math.floor(elapsed / (10 + STEP_S));
  if (i >= STAGES.length) return 'ramp';
  return elapsed - i * (10 + STEP_S) < 10 ? 'ramp' : `r${STAGES[i]}`;
}

const stages = [];
for (const r of STAGES) {
  stages.push({ target: r, duration: '10s' });   // ramp to this step
  stages.push({ target: r, duration: STEP });     // hold
}

export const options = {
  scenarios: {
    main: {
      executor: 'ramping-arrival-rate', startRate: STAGES[0], timeUnit: '1s', stages,
      preAllocatedVUs: 100, maxVUs: parseInt(__ENV.MAX_VUS || '400', 10),
    },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(['create_order']),
    ...bizThresholds('biz_order', ORDER_RESULTS.map((r) => ({ tenant: '901', result: r }))),
    ...Object.fromEntries(STAGES.flatMap((r) => [
      [`http_req_duration{name:create_order,stage:r${r}}`, ['max>=0']],
      [`http_reqs{name:create_order,stage:r${r}}`, ['count>=0']],
      [`http_req_failed{name:create_order,stage:r${r}}`, ['rate>=0']],
    ])),
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export default function (data) {
  exec.vu.metrics.tags.stage = stageTag();
  createOrder(pick(data.buyers), config.bigTenant, [{ skuId: pick(SKUS), quantity: 1 }]);
}

export const handleSummary = makeHandleSummary(HOT ? 'S2a hot SKU' : 'S2a control: 10 SKUs', { stageSeconds: STEP_S });
