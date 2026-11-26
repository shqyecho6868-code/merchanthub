// All tunables come from env vars (k6 -e KEY=VALUE, or passed through by perf/run.sh); no hard-coded addresses or sizes.
// In the cloud only BASE_URL / VUS / DURATION etc. change; the scripts stay the same.

const env = (k, d) => (__ENV[k] !== undefined && __ENV[k] !== '' ? __ENV[k] : d);

export const config = {
  // Through the gateway (default): real auth + routing, measuring the latency users see.
  // When k6 runs in a container the host is host.docker.internal (perf/run.sh handles it)
  baseUrl: env('BASE_URL', 'http://127.0.0.1:10010/api'),
  // MODE=direct: bypass the gateway and call services directly with identity headers (downstream services trust them). Used to
  // locate bottlenecks layer by layer: the latency gap between gateway and direct under the same load is the gateway's overhead
  mode: env('MODE', 'gateway'),
  directUrls: {
    order: env('ORDER_URL', 'http://127.0.0.1:8107'),
    payment: env('PAYMENT_URL', 'http://127.0.0.1:8108'),
  },

  // Load shape (each scenario picks which ones to use)
  vus: parseInt(env('VUS', '10'), 10),
  duration: env('DURATION', '30s'),
  rate: parseInt(env('RATE', '50'), 10),            // constant-arrival-rate scenarios: iterations started per second
  rampUp: env('RAMP_UP', '10s'),

  // Load-test data (matches perf/data/seed.sql)
  bigTenant: parseInt(env('BIG_TENANT', '901'), 10),
  smallTenant: parseInt(env('SMALL_TENANT', '902'), 10),
  bigSkus: env('BIG_SKUS', '90101,90102,90103,90104,90105,90106,90107,90108,90109,90110').split(',').map(Number),
  smallSkus: env('SMALL_SKUS', '90201').split(',').map(Number),
  buyerCount: parseInt(env('BUYERS', '200'), 10),   // how many load-test buyers setup() logs in (up to the 200 in the seed)
  buyerPassword: env('BUYER_PASSWORD', 'password123'),

  timeout: env('HTTP_TIMEOUT', '10s'),
};

export function buyerUsername(i) {
  return `perf_buyer_${String(i).padStart(4, '0')}`;
}
