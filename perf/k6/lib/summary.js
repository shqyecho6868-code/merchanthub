// F1：压测结束时输出三份结果到 perf/results/<RUN_ID>/：
//   summary.json  k6 原始汇总（全部指标）
//   summary.md    按接口一行：请求数、QPS、P50、P90、P99、错误率 —— 直接贴进 perf-report / README
//   以及终端里的标准文本汇总
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// 场景脚本 options 里要写 summaryTrendStats，P50 / P99 才会出现在汇总里
export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

function fmt(v) {
  return v === undefined || v === null ? '-' : v.toFixed(1);
}

function rowsByName(data) {
  // 按 name 标签拆分的子指标形如 http_req_duration{name:create_order}，需要在 thresholds 里声明才会出现
  const rows = [];
  const durSec = data.state.testRunDurationMs / 1000;
  for (const key of Object.keys(data.metrics)) {
    const m = /^http_req_duration\{name:(.+)\}$/.exec(key);
    if (!m) continue;
    const name = m[1];
    const d = data.metrics[key].values;
    const reqs = data.metrics[`http_reqs{name:${name}}`];
    const failed = data.metrics[`http_req_failed{name:${name}}`];
    const count = reqs ? reqs.values.count : 0;
    rows.push({
      name,
      count,
      qps: durSec > 0 ? count / durSec : 0,
      p50: d.med,
      p90: d['p(90)'],
      p99: d['p(99)'],
      errRate: failed ? failed.values.rate * 100 : 0,
    });
  }
  return rows;
}

export function makeHandleSummary(scenarioName) {
  return function (data) {
    const runId = __ENV.RUN_ID || `${scenarioName}-${Date.now()}`;
    const dir = `${__ENV.RESULT_DIR || 'perf/results'}/${runId}`;
    const rows = rowsByName(data);
    const checks = data.metrics.checks ? data.metrics.checks.values.rate * 100 : 0;
    let md = `# 压测结果：${scenarioName}\n\n`;
    md += `- 运行 ID：${runId}\n- 时长：${(data.state.testRunDurationMs / 1000).toFixed(1)}s\n`;
    md += `- 参数：BASE_URL=${__ENV.BASE_URL || '-'} MODE=${__ENV.MODE || 'gateway'} VUS=${__ENV.VUS || '-'} DURATION=${__ENV.DURATION || '-'} RATE=${__ENV.RATE || '-'}\n`;
    md += `- 业务断言通过率（checks）：${checks.toFixed(2)}%\n\n`;
    md += '| 接口 | 请求数 | QPS | P50 (ms) | P90 (ms) | P99 (ms) | HTTP 错误率 |\n|---|---|---|---|---|---|---|\n';
    for (const r of rows) {
      md += `| ${r.name} | ${r.count} | ${fmt(r.qps)} | ${fmt(r.p50)} | ${fmt(r.p90)} | ${fmt(r.p99)} | ${r.errRate.toFixed(2)}% |\n`;
    }
    md += '\n> HTTP 错误率只算 4xx/5xx 与超时；限流 429 也计入。业务失败（HTTP 200 但 code≠200，如库存不足）看 checks。\n';
    return {
      stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n\n' + md,
      [`${dir}/summary.json`]: JSON.stringify(data, null, 2),
      [`${dir}/summary.md`]: md,
    };
  };
}

/** 给每个接口声明一个"永远成立"的阈值，目的只是让 k6 产出按 name 拆分的子指标 */
export function perEndpointThresholds(names) {
  const t = {};
  for (const n of names) {
    t[`http_req_duration{name:${n}}`] = ['max>=0'];
    t[`http_reqs{name:${n}}`] = ['count>=0'];
    t[`http_req_failed{name:${n}}`] = ['rate>=0'];
  }
  return t;
}
