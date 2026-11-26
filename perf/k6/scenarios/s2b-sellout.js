// S2b sell-out correctness
// Question: with only 500 in stock and 2000 concurrent requests, does it sell exactly out without overselling a single unit?
// Load: 200 VUs (one buyer each) place TOTAL (default 2000) orders as fast as possible
// Expected: exactly 500 succeed; the rest are all "out of stock" (61001), no 5xx / timeouts; stock 0, never negative (check.sh passes)
// Prerequisite: HOT_STOCK=500 ./perf/data/seed.sh (sets only SKU 90101 to 500); raise 901's rate limit
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

const VUS = parseInt(__ENV.VUS || '200', 10);

export const options = {
  scenarios: {
    main: { executor: 'shared-iterations', vus: VUS, iterations: parseInt(__ENV.TOTAL || '2000', 10), maxDuration: '5m' },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(['create_order']),
    ...bizThresholds('biz_order', ORDER_RESULTS.map((r) => ({ tenant: '901', result: r }))),
    // "No real errors" as a threshold: k6 exits non-zero if any error occurs
    'biz_order{tenant:901,result:error}': ['count==0'],
  },
};

export function setup() {
  return { buyers: loginBuyers(Math.min(VUS, config.buyerCount)) };
}

export default function (data) {
  // Each VU sticks to one buyer: 200 different people competing at once
  const buyer = data.buyers[(__VU - 1) % data.buyers.length];
  createOrder(buyer, config.bigTenant, [{ skuId: config.bigSkus[0], quantity: 1 }]);
}

export const handleSummary = makeHandleSummary('S2b sell-out');
