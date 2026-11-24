// S3 重复回调（场景设计：我 + Claude 补充；见 docs/perf-report.md 第 2 节）
// 问题：压力下同一笔支付的回调连发 3 次，还能只生效一次吗？
// 压力：每秒 RATE（默认 20）笔完整交易，DURATION（默认 1m）；每笔：下单 → 建支付单 → 同一笔回调用 http.batch 并发发 DUP（默认 3）次
// 预期：PAID 数 = 交易数，DUPLICATE = (DUP-1) × 交易数（极少数可能是 IGNORED）；check.sh 支付相关项全过
// 前提：901 限流提额
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
  // 同一 channelTradeNo = 渠道对同一笔支付的重复通知；batch 让它们真正并发到达
  const req = callbackRequest(pay.payNo, `PERF-${pay.payNo}`, pay.amount);
  const resps = http.batch(Array.from({ length: DUP }, () => req));
  for (const r of resps) callbackResult(r);
}

export const handleSummary = makeHandleSummary('S3 重复回调');
