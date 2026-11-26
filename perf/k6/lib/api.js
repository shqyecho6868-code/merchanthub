// Business API wrappers. Every request gets a name tag so k6 reports P50 / P99 / error rate per endpoint
// (otherwise order numbers in URLs would split them into countless series).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { config, buyerUsername } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Count by business outcome (k6's built-in metrics only know HTTP success / failure and can't tell rate-limited, out-of-stock and real errors apart).
// Tagged with tenant / result; summary.js renders them as a table; check.sh reconciles biz_order{result:ok} against orders in the DB
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
  // gateway mode: /api/orders, /api/payments; direct mode: straight to the order / payment ports
  if (config.mode === 'direct') {
    return config.directUrls[service] + path;
  }
  return config.baseUrl + path;
}

function authHeaders(buyer) {
  if (config.mode === 'direct') {
    // When bypassing the gateway, send the identity headers the gateway would normally inject
    return { ...JSON_HEADERS, 'X-User-Id': String(buyer.userId), 'X-Role': 'BUYER' };
  }
  return { ...JSON_HEADERS, 'Access-Token': buyer.token };
}

/** Unified response body {code, data, message}: business success only when HTTP 200 and code=200 (ResultUtils.success in Common) */
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
 * Called in setup(): log in N load-test buyers concurrently, returns [{userId, token}].
 * Login goes through the real gateway API (BCrypt check + access token written to Redis); direct mode also logs in once to get userId
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
  // Batches of 20: BCrypt is deliberately slow (cost 10, tens of ms), sending all at once would saturate user-tenant
  for (let i = 0; i < reqs.length; i += 20) {
    const resps = http.batch(reqs.slice(i, i + 20));
    for (const r of resps) {
      const b = body(r);
      if (!bizOk(r)) {
        throw new Error(`Load-test buyer login failed (did you run perf/data/seed.sh?): HTTP ${r.status} ${r.body}`);
      }
      buyers.push({ userId: Number(b.data.userId), token: b.data.accessToken });
    }
  }
  return buyers;
}

/** Place an order, returns {res, orderNo, payAmount} (orderNo is null on failure) */
export function createOrder(buyer, tenantId, items) {
  const res = http.post(url('order', '/orders'), JSON.stringify({ tenantId, items }),
    { headers: authHeaders(buyer), tags: { name: 'create_order' }, timeout: config.timeout });
  const parsed = body(res);
  const result = classifyOrder(res, parsed);
  bizOrder.add(1, { tenant: String(tenantId), result });
  // Rate-limited and out-of-stock are by-design rejections, not failures; only error is a problem
  check(res, { 'order did not error (success / rate-limited / out of stock are all fine)': () => result !== 'error' });
  const b = result === 'ok' ? parsed : null;
  return { res, result, orderNo: b ? b.data.orderNo : null, payAmount: b ? b.data.payAmount : null };
}

/** Create a payment, returns {res, payNo, amount} */
export function createPayment(buyer, orderNo) {
  // orderNo is a 19-digit string (beyond JS safe integers); put it into the JSON as is, never convert to Number
  const res = http.post(url('payment', '/payments'), `{"orderNo":${orderNo}}`,
    { headers: authHeaders(buyer), tags: { name: 'create_payment' }, timeout: config.timeout });
  const ok = bizOk(res);
  check(res, { 'payment created': () => ok });
  // payNo is also beyond safe integers: extract it from the raw body with a regex so res.json() doesn't lose precision
  const m = ok ? /"payNo":(\d+)/.exec(res.body) : null;
  const a = ok ? /"amount":(\d+)/.exec(res.body) : null;
  return { res, payNo: m ? m[1] : null, amount: a ? a[1] : null };
}

/**
 * Mock channel callback. Repeating the same channelTradeNo = duplicate channel notifications; the server must apply it once (returns PAID / DUPLICATE / IGNORED)
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

/** Parse one callback response and count it (also reused when http.batch sends callbacks concurrently) */
export function callbackResult(res) {
  const ok = bizOk(res);
  check(res, { 'callback accepted': () => ok });
  const b = ok ? body(res) : null;
  const result = b ? b.data : 'error';
  bizCallback.add(1, { result: CALLBACK_RESULTS.includes(result) ? result : 'error' });
  return { res, result };
}

/** Buyer queries their own order */
export function getMyOrder(buyer, orderNo) {
  const res = http.get(url('order', `/orders/mine/${orderNo}`),
    { headers: authHeaders(buyer), tags: { name: 'get_my_order' }, timeout: config.timeout });
  check(res, { 'order query ok': () => bizOk(res) });
  return res;
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
