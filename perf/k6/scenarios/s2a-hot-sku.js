// S2a 热点性能（场景设计：我；见 docs/perf-report.md 第 2 节）
// 问题：所有请求都抢同一行库存时，下单会慢多少？瓶颈在哪？
// 压力：阶梯到达率 STAGES（默认 20,50,100,200 单/秒），每级 STEP（默认 1m），级间 10s 过渡
//   热点组：-e HOT=1      → 只打 SKU 90101（同一行 inventory）
//   对照组：-e HOT=0      → 随机打 901 的 10 个 SKU
// 看：每级 QPS / P99 / dropped_iterations；Grafana 的 Hikari pending、Tomcat busy；sample.sh 的行锁等待
// 预期：找到拐点（QPS 不再涨、P99 陡增、开始丢迭代的那一级）；热点组 P99 明显高于对照组；check.sh 全过
// 前提：901 限流提额（ORDER_OPTS='--merchanthub.order.sentinel.rate-limit.tenant-overrides[901]=100000'）
import exec from 'k6/execution';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, pick, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

const HOT = (__ENV.HOT || '1') === '1';
const STAGES = (__ENV.STAGES || '20,50,100,200').split(',').map(Number);
const STEP = __ENV.STEP || '1m';
const SKUS = HOT ? [config.bigSkus[0]] : config.bigSkus;

// 每一级的"保持段"打上 stage 标签（r20 / r50 ...），过渡段打 ramp，汇总时按级出 P50 / P99（拐点在哪一级）
const STEP_S = parseInt(STEP, 10) * (STEP.endsWith('m') ? 60 : 1);
function stageTag() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  const i = Math.floor(elapsed / (10 + STEP_S));
  if (i >= STAGES.length) return 'ramp';
  return elapsed - i * (10 + STEP_S) < 10 ? 'ramp' : `r${STAGES[i]}`;
}

const stages = [];
for (const r of STAGES) {
  stages.push({ target: r, duration: '10s' });   // 过渡到这一级
  stages.push({ target: r, duration: STEP });     // 保持
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

export const handleSummary = makeHandleSummary(HOT ? 'S2a 热点 SKU' : 'S2a 对照：10 个 SKU', { stageSeconds: STEP_S });
