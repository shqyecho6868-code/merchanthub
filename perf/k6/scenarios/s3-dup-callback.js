// S3 duplicate callbacks
// Question: under load, if the same payment callback arrives 3 times, does it still take effect only once?
// Load: RATE (default 20) full transactions/s for DURATION (default 1m); each: order -> create payment -> send the same callback DUP (default 3) times concurrently via http.batch
// Expected: PAID count = transactions, DUPLICATE = (DUP-1) x transactions (a few may be IGNORED); payment checks in check.sh pass
// Prerequisite: raise 901's rate limit
import http from 'k6/http';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, createPayment, callbackRequest, callbackResult, pick, ORDER_RESULTS, CALLBACK_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

const DUP = parseInt(__ENV.DUP || '3', 10);

export const options = {
  scenarios: {
    main: {
      executor: 'constant-arrival-rate', rate: parseInt(__ENV.RATE || '20', 10), timeUnit: '1s',
      duration: __ENV.DURATION || '1m', preAllocatedVUs: 40, maxVUs: 200,
    },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(['create_order', 'create_payment', 'pay_callback']),
    ...bizThresholds('biz_order', ORDER_RESULTS.map((r) => ({ tenant: '901', result: r }))),
    ...bizThresholds('biz_callback', CALLBACK_RESULTS.map((r) => ({ result: r }))),
    'biz_callback{result:error}': ['count==0'],
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export default function (data) {
  const buyer = pick(data.buyers);
  const order = createOrder(buyer, config.bigTenant, [{ skuId: pick(config.bigSkus), quantity: 1 }]);
  if (!order.orderNo) return;
  const pay = createPayment(buyer, order.orderNo);
  if (!pay.payNo) return;
  // Same channelTradeNo = the channel re-notifying the same payment; batch makes them arrive truly concurrently
  const req = callbackRequest(pay.payNo, `PERF-${pay.payNo}`, pay.amount);
  const resps = http.batch(Array.from({ length: DUP }, () => req));
  for (const r of resps) callbackResult(r);
}

export const handleSummary = makeHandleSummary('S3 duplicate callbacks');
