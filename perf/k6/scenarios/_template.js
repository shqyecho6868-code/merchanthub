// Scenario template: copy and rename this file (e.g. s1-xxx.js) and change only the TODO parts.
// Run: ./perf/run.sh perf/k6/scenarios/s1-xxx.js -e VUS=50 -e DURATION=1m
//
// A scenario answers 5 questions (put them in the header comment and compare after the run):
//   1. Which endpoint / business flow?   2. How much concurrency (or requests per second)?   3. For how long?
//   4. Which metrics to watch?            5. Expected result (P99 < ? ms, error rate < ?%, all data checks pass)?
import { sleep } from 'k6';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, createPayment, mockCallback, getMyOrder, pick } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, TREND_STATS } from '../lib/summary.js';

// TODO: endpoints this scenario calls (same name tags as in api.js), for per-endpoint P50 / P99
const ENDPOINTS = ['create_order'];

export const options = {
  // TODO: pick one load model; all params come from env vars (see lib/config.js), never hard-code
  // (1) Fixed concurrency ("how many users at the same time"):
  scenarios: {
    main: {
      executor: 'constant-vus',
      vus: config.vus,
      duration: config.duration,
    },
    // (2) Fixed arrival rate ("requests per second"; doesn't back off when the system slows down, closer to real traffic):
    // main: {
    //   executor: 'constant-arrival-rate',
    //   rate: config.rate, timeUnit: '1s', duration: config.duration,
    //   preAllocatedVUs: config.vus, maxVUs: config.vus * 4,
    // },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(ENDPOINTS),
    // TODO: express the expected result as thresholds so k6 exits non-zero when missed, e.g.:
    // 'http_req_duration{name:create_order}': ['p(99)<500'],
    // http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export default function (data) {
  const buyer = pick(data.buyers);
  // TODO: one iteration = one thing one user does
  createOrder(buyer, config.bigTenant, [{ skuId: pick(config.bigSkus), quantity: 1 }]);
  sleep(0);
}

export const handleSummary = makeHandleSummary('TODO-scenario-name');
