// S1 限流（场景设计：我；见 docs/perf-report.md 第 2 节）
// 问题：流量过大时系统怎么处理、处理得怎么样？被限流拒掉的请求会不会留下错误订单？
// 压力：大商家 901 每秒 BIG_RATE 单（默认 100，限流阈值 20）+ 小商家 902 每秒 SMALL_RATE 单（默认 5），同时压 DURATION（默认 1m）
// 预期：901 约 20/秒通过、其余 429 且很快返回；902 全部成功；k6 成功数 = 库里订单数（check.sh <RUN_ID>）；检查全过
// 前提：order 用默认限流配置启动（不要带 tenant-overrides）
import http from 'k6/http';
import { config } from '../lib/config.js';
import { loginBuyers, createOrder, pick, ORDER_RESULTS } from '../lib/api.js';
import { makeHandleSummary, perEndpointThresholds, bizThresholds, TREND_STATS } from '../lib/summary.js';

// 429 是本场景"按设计拒绝"的结果，不计入 http_req_failed（否则错误率全是限流，看不出真错误）
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
    // 按商家分开看延迟：被限流的 901 请求应该很快（入口直接拒），902 不应被拖慢
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

export const handleSummary = makeHandleSummary('S1 限流');
