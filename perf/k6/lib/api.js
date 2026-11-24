// F1：业务接口封装。每个请求打 name 标签，k6 按接口分别统计 P50 / P99 / 错误率（否则 URL 里带订单号会被拆成无数条）。
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { config, buyerUsername } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// F1 第二部分：按"业务结果"计数（k6 自带指标只分 HTTP 成功 / 失败，分不出限流、库存不足、真错误）。
// 带 tenant / result 标签，summary.js 会把它们列成一张表；check.sh 用 biz_order{result:ok} 和库里订单数对账
export const bizOrder = new Counter('biz_order');
export const bizCallback = new Counter('biz_callback');
export const ORDER_RESULTS = ['ok', 'rate_limited', 'insufficient', 'error'];
export const CALLBACK_RESULTS = ['PAID', 'DUPLICATE', 'IGNORED', 'error'];

function classifyOrder(res, b) {
  if (res.status === 200 && b && b.code === 200) return 'ok';
  if (res.status === 429) return 'rate_limited';
  if (b && b.code === 61001) return 'insufficient';   // ErrorCode.INVENTORY_INSUFFICIENT
  return 'error';
}

function url(service, path) {
  // gateway 模式：/api/orders、/api/payments；direct 模式：直连 order / payment 端口
  if (config.mode === 'direct') {
    return config.directUrls[service] + path;
  }
  return config.baseUrl + path;
}

function authHeaders(buyer) {
  if (config.mode === 'direct') {
    // 绕过网关时自己带网关本该注入的身份头
    return { ...JSON_HEADERS, 'X-User-Id': String(buyer.userId), 'X-Role': 'BUYER' };
  }
  return { ...JSON_HEADERS, 'Access-Token': buyer.token };
}

/** 统一返回体 {code, data, message}：HTTP 200 且 code=200 才算业务成功（Common 的 ResultUtils.success） */
function body(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

export function bizOk(res) {
  const b = body(res);
  return res.status === 200 && b !== null && b.code === 200;
}

/**
 * setup() 里调用：并发登录 N 个压测买家，返回 [{userId, token}]。
 * 登录走网关真实接口（BCrypt 校验 + 写 Redis 里的 access token），direct 模式也登录一次拿 userId
 */
export function loginBuyers(n) {
  const loginUrl = config.baseUrl + '/user/login';
  const reqs = [];
  for (let i = 1; i <= n; i++) {
    reqs.push(['POST', loginUrl,
      JSON.stringify({ username: buyerUsername(i), password: config.buyerPassword }),
      { headers: JSON_HEADERS, tags: { name: 'setup_login' }, timeout: '30s' }]);
  }
  const buyers = [];
  // 每批 20 个：BCrypt 故意慢（cost 10 约几十毫秒），一次全发会把 user-tenant 打满
  for (let i = 0; i < reqs.length; i += 20) {
    const resps = http.batch(reqs.slice(i, i + 20));
    for (const r of resps) {
      const b = body(r);
      if (!bizOk(r)) {
        throw new Error(`压测买家登录失败（先跑 perf/data/seed.sh？）：HTTP ${r.status} ${r.body}`);
      }
      buyers.push({ userId: Number(b.data.userId), token: b.data.accessToken });
    }
  }
  return buyers;
}

/** 下单，返回 {res, orderNo, payAmount}（失败时 orderNo 为 null） */
export function createOrder(buyer, tenantId, items) {
  const res = http.post(url('order', '/orders'), JSON.stringify({ tenantId, items }),
    { headers: authHeaders(buyer), tags: { name: 'create_order' }, timeout: config.timeout });
  const parsed = body(res);
  const result = classifyOrder(res, parsed);
  bizOrder.add(1, { tenant: String(tenantId), result });
  // 限流和库存不足是"系统按设计拒绝"，不算失败；只有 error 才是问题
  check(res, { '下单没有出错（成功 / 限流 / 库存不足都算正常）': () => result !== 'error' });
  const b = result === 'ok' ? parsed : null;
  return { res, result, orderNo: b ? b.data.orderNo : null, payAmount: b ? b.data.payAmount : null };
}

/** 建支付单，返回 {res, payNo, amount} */
export function createPayment(buyer, orderNo) {
  // orderNo 是 19 位字符串（超过 JS 安全整数），直接拼进 JSON，不能先转 Number
  const res = http.post(url('payment', '/payments'), `{"orderNo":${orderNo}}`,
    { headers: authHeaders(buyer), tags: { name: 'create_payment' }, timeout: config.timeout });
  const ok = bizOk(res);
  check(res, { '建支付单成功': () => ok });
  // payNo 同样超过安全整数：从原始响应体里用正则取，避免 res.json() 丢精度
  const m = ok ? /"payNo":(\d+)/.exec(res.body) : null;
  const a = ok ? /"amount":(\d+)/.exec(res.body) : null;
  return { res, payNo: m ? m[1] : null, amount: a ? a[1] : null };
}

/**
 * 模拟渠道回调。同一 channelTradeNo 重复调用 = 渠道重复通知，服务端应只生效一次（返回 PAID / DUPLICATE / IGNORED）
 */
export function callbackRequest(payNo, channelTradeNo, amount) {
  return ['POST', url('payment', '/payments/callback/mock'),
    `{"payNo":${payNo},"channelTradeNo":"${channelTradeNo}","amount":${amount}}`,
    { headers: JSON_HEADERS, tags: { name: 'pay_callback' }, timeout: config.timeout }];
}

export function mockCallback(payNo, channelTradeNo, amount) {
  const [m, u, b, p] = callbackRequest(payNo, channelTradeNo, amount);
  const res = http.request(m, u, b, p);
  return callbackResult(res);
}

/** 解析一次回调响应并计数（http.batch 并发发回调时也复用） */
export function callbackResult(res) {
  const ok = bizOk(res);
  check(res, { '回调已受理': () => ok });
  const b = ok ? body(res) : null;
  const result = b ? b.data : 'error';
  bizCallback.add(1, { result: CALLBACK_RESULTS.includes(result) ? result : 'error' });
  return { res, result };
}

/** 买家查自己的订单 */
export function getMyOrder(buyer, orderNo) {
  const res = http.get(url('order', `/orders/mine/${orderNo}`),
    { headers: authHeaders(buyer), tags: { name: 'get_my_order' }, timeout: config.timeout });
  check(res, { '查单成功': () => bizOk(res) });
  return res;
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
