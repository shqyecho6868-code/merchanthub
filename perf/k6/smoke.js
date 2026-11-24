// F1 冒烟脚本：不是压测场景，只验证"压测框架 + 数据 + 服务"这条链路是通的。
// 1 个 VU 跑几轮完整交易：下单 → 建支付单 → 同一笔支付回调 2 次（第二次应为 DUPLICATE）→ 查单。
// 用法：./perf/run.sh perf/k6/smoke.js        （压测场景由 🧑 在 perf/k6/scenarios/ 下设计）
import { sleep } from 'k6';
import { config } from './lib/config.js';
import { loginBuyers, createOrder, createPayment, mockCallback, getMyOrder, pick } from './lib/api.js';
import { makeHandleSummary, perEndpointThresholds, TREND_STATS } from './lib/summary.js';

const ENDPOINTS = ['create_order', 'create_payment', 'pay_callback', 'get_my_order'];

export const options = {
  vus: 1,
  iterations: parseInt(__ENV.ITERATIONS || '5', 10),
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(ENDPOINTS),
    checks: ['rate==1'],          // 冒烟要求全部成功，否则 k6 退出码非 0
  },
};

export function setup() {
  return { buyers: loginBuyers(Math.min(config.buyerCount, 2)) };
}

export default function (data) {
  const buyer = pick(data.buyers);
  const tenantId = __ITER % 2 === 0 ? config.bigTenant : config.smallTenant;
  const sku = pick(tenantId === config.bigTenant ? config.bigSkus : config.smallSkus);

  const order = createOrder(buyer, tenantId, [{ skuId: sku, quantity: 1 }]);
  if (!order.orderNo) return;
  const pay = createPayment(buyer, order.orderNo);
  if (!pay.payNo) return;
  const tradeNo = `PERF-${pay.payNo}`;
  mockCallback(pay.payNo, tradeNo, pay.amount);
  mockCallback(pay.payNo, tradeNo, pay.amount);   // 渠道重复通知：期望 DUPLICATE，只生效一次
  getMyOrder(buyer, order.orderNo);
  sleep(0.2);
}

export const handleSummary = makeHandleSummary('smoke');
