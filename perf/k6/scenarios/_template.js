// F1 场景模板：🧑 场景由你设计后，复制本文件改名（如 s1-xxx.js），只改标了 TODO 的地方。
// 跑：./perf/run.sh perf/k6/scenarios/s1-xxx.js -e VUS=50 -e DURATION=1m
//
// 设计一个场景要回答 5 件事（写进文件头注释，压完对照）：
//   1. 压什么接口 / 业务流程？   2. 多少并发（或每秒多少请求）？   3. 压多久？
//   4. 看哪些指标？              5. 预期结果是什么（P99 < ? ms、错误率 < ?%、数据检查全过）？
import { sleep } from 'k6';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, createPayment, mockCallback, getMyOrder, pick } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, TREND_STATS } from '../lib/summary.js';

// TODO：本场景会调用的接口（与 api.js 里的 name 标签一致），用于按接口出 P50 / P99
const ENDPOINTS = ['create_order'];

export const options = {
  // TODO：负载模型二选一，参数全部来自环境变量（见 lib/config.js），不要写死
  // ① 固定并发（"同时有多少人在用"）：
  scenarios: {
    main: {
      executor: 'constant-vus',
      vus: config.vus,
      duration: config.duration,
    },
    // ② 固定到达率（"每秒来多少请求"，系统变慢也不会自动减压，更接近真实流量）：
    // main: {
    //   executor: 'constant-arrival-rate',
    //   rate: config.rate, timeUnit: '1s', duration: config.duration,
    //   preAllocatedVUs: config.vus, maxVUs: config.vus * 4,
    // },
  },
  summaryTrendStats: TREND_STATS,
  thresholds: {
    ...perEndpointThresholds(ENDPOINTS),
    // TODO：把"预期结果"写成阈值，不达标时 k6 退出码非 0，例如：
    // 'http_req_duration{name:create_order}': ['p(99)<500'],
    // http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { buyers: loginBuyers(config.buyerCount) };
}

export default function (data) {
  const buyer = pick(data.buyers);
  // TODO：一次迭代 = 一个用户做的一件事
  createOrder(buyer, config.bigTenant, [{ skuId: pick(config.bigSkus), quantity: 1 }]);
  sleep(0);
}

export const handleSummary = makeHandleSummary('TODO-场景名');
