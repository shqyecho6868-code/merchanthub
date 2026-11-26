// G-下 S4 稳态（扩容对比 / 故障演练用）：固定到达率持续压一段时间，配合 --out csv 按 5 秒切片看时间线
// 问题：同一个到达率下，order 1→3 个实例 / 停掉一个实例时，吞吐、P99、错误率随时间怎么变？
// 压力：constant-arrival-rate，RATE 单/秒，持续 DURATION；HOT=1 只打 SKU 90101，默认 10 个 SKU 分散
// 看：perf/cloud/timeline.py 输出的每 5 秒 完成数 / 错误数 / P99；压完 perf/cloud/check.sh 核对数据
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

export const handleSummary = makeHandleSummary(`S4 稳态 ${config.rate}/s${HOT ? '（热点）' : ''}`);
