// S2b 卖光正确性（场景设计：我；见 docs/perf-report.md 第 2 节）
// 问题：库存只有 500，2000 个请求同时抢，能不能恰好卖完、一件不多卖？
// 压力：200 个 VU（每个一个买家）共 TOTAL（默认 2000）次下单，尽快打完
// 预期：成功正好 500；其余全是"库存不足"（61001），没有 5xx / 超时；库存 0、无负数（check.sh 全过）
// 前提：HOT_STOCK=500 ./perf/data/seed.sh（只把 SKU 90101 库存设为 500）；901 限流提额
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
    // 预期里"没有真错误"写成阈值：出现 error 时 k6 退出码非 0
    'biz_order{tenant:901,result:error}': ['count==0'],
  },
};

export function setup() {
  return { buyers: loginBuyers(Math.min(VUS, config.buyerCount)) };
}

export default function (data) {
  // 每个 VU 固定用一个买家：200 个不同的人同时抢
  const buyer = data.buyers[(__VU - 1) % data.buyers.length];
  createOrder(buyer, config.bigTenant, [{ skuId: config.bigSkus[0], quantity: 1 }]);
}

export const handleSummary = makeHandleSummary('S2b 卖光');
