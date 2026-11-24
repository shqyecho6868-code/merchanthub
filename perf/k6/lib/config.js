// F1：所有可调参数都从环境变量读（k6 用 -e KEY=VALUE 或 perf/run.sh 透传），脚本里不写死地址和规模。
// G 阶段上云时只改 BASE_URL / VUS / DURATION 等变量，脚本本身不动。

const env = (k, d) => (__ENV[k] !== undefined && __ENV[k] !== '' ? __ENV[k] : d);

export const config = {
  // 经网关压（默认）：走真实鉴权 + 路由，测的是"用户看到的"延迟。
  // 容器里跑 k6 时宿主机地址是 host.docker.internal（perf/run.sh 会处理）
  baseUrl: env('BASE_URL', 'http://127.0.0.1:10010/api'),
  // MODE=direct：绕过网关直连各服务，自带身份头（C1：下游信任身份头）。用来分层定位瓶颈：
  // 同样的压力下"经网关"和"直连"的延迟差，就是网关这一层的开销
  mode: env('MODE', 'gateway'),
  directUrls: {
    order: env('ORDER_URL', 'http://127.0.0.1:8107'),
    payment: env('PAYMENT_URL', 'http://127.0.0.1:8108'),
  },

  // 负载形状（场景脚本自己决定用哪几个）
  vus: parseInt(env('VUS', '10'), 10),
  duration: env('DURATION', '30s'),
  rate: parseInt(env('RATE', '50'), 10),            // 恒定到达率场景：每秒发起多少次迭代
  rampUp: env('RAMP_UP', '10s'),

  // 压测数据（与 perf/data/seed.sql 对应）
  bigTenant: parseInt(env('BIG_TENANT', '901'), 10),
  smallTenant: parseInt(env('SMALL_TENANT', '902'), 10),
  bigSkus: env('BIG_SKUS', '90101,90102,90103,90104,90105,90106,90107,90108,90109,90110').split(',').map(Number),
  smallSkus: env('SMALL_SKUS', '90201').split(',').map(Number),
  buyerCount: parseInt(env('BUYERS', '200'), 10),   // setup() 登录多少个压测买家（最多 seed 里的 200 个）
  buyerPassword: env('BUYER_PASSWORD', 'password123'),

  timeout: env('HTTP_TIMEOUT', '10s'),
};

export function buyerUsername(i) {
  return `perf_buyer_${String(i).padStart(4, '0')}`;
}
