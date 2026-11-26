// S1 rate limiting
// Question: how does the system handle excess traffic, and how well? Do rate-limited requests leave bad orders behind?
// Load: big merchant 901 at BIG_RATE orders/s (default 100, limit 20) + small merchant 902 at SMALL_RATE orders/s (default 5), both for DURATION (default 1m)
// Expected: ~20/s pass for 901, the rest get a fast 429; 902 all succeed; k6 successes = orders in DB (check.sh <RUN_ID>); all checks pass
// Prerequisite: order runs with the default rate-limit config (no tenant-overrides)
import http from 'k6/http';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, pick, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

// 429 is the by-design rejection in this scenario, so it is excluded from http_req_failed (otherwise the error rate would be all rate limiting and hide real errors)
http.setResponseCallback(http.expectedStatuses(200, 429));

const BIG_RATE = parseInt(__ENV.BIG_RATE || '100', 10);
const SMALL_RATE = parseInt(__ENV.SMALL_RATE || '5', 10);
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    big: {
      executor: 'constant-arrival-rate', exec: 'big', rate: BIG_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 200,
    },
    small: {
      executor: 'constant-arrival-rate', exec: 'small', rate: SMALL_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 5, maxVUs: 20,
    },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(['create_order']),
    // Latency per merchant: rate-limited 901 requests should be fast (rejected at the entry), 902 must not slow down
    'http_req_duration{scenario:big}': ['max>=0'],
    'http_req_duration{scenario:small}': ['max>=0'],
    ...bizThresholds('biz_order', ['901', '902'].flatMap((t) => ORDER_RESULTS.map((r) => ({ tenant: t, result: r })))),
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export function big(data) {
  createOrder(pick(data.buyers), config.bigTenant, [{ skuId: pick(config.bigSkus), quantity: 1 }]);
}

export function small(data) {
  createOrder(pick(data.buyers), config.smallTenant, [{ skuId: pick(config.smallSkus), quantity: 1 }]);
}

export const handleSummary = makeHandleSummary('S1 rate limiting');
