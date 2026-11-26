// S4 steady state (for scale-out comparison / failure drills): fixed arrival rate for a while; with --out csv, view the timeline in 5s buckets
// Question: at the same arrival rate, how do throughput, P99 and error rate change over time when order goes 1 -> 3 instances / one instance is stopped?
// Load: constant-arrival-rate, RATE orders/s for DURATION; HOT=1 hits only SKU 90101, default spreads across 10 SKUs
// Watch: per-5s completed / errors / P99 from perf/cloud/timeline.py; afterwards verify data with perf/cloud/check.sh
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, pick, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

const HOT = (__ENV.HOT || '0') === '1';
const SKUS = HOT ? [config.bigSkus[0]] : config.bigSkus;

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate', rate: config.rate, timeUnit: '1s', duration: config.duration,
      preAllocatedVUs: 200, maxVUs: parseInt(__ENV.MAX_VUS || '800', 10),
    },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(['create_order']),
    ...bizThresholds('biz_order', ORDER_RESULTS.map((r) => ({ tenant: '901', result: r }))),
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export default function (data) {
  createOrder(pick(data.buyers), config.bigTenant, [{ skuId: pick(SKUS), quantity: 1 }]);
}

export const handleSummary = makeHandleSummary(`S4 steady ${config.rate}/s${HOT ? ' (hot)' : ''}`);
