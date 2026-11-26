// At the end of a run, write results to perf/results/<RUN_ID>/:
//   summary.json  k6's raw summary (all metrics)
//   summary.md    one row per endpoint: requests, QPS, P50, P90, P99, error rate (ready to paste into a report / README)
//   plus the standard text summary in the terminal
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// Scenario options must set summaryTrendStats for P50 / P99 to appear in the summary
export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

function fmt(v) {
  return v === undefined || v === null ? '-' : v.toFixed(1);
}

function rowsByName(data, opts) {
  // Per-name sub-metrics like http_req_duration{name:create_order} only appear if declared in thresholds;
  // stepped scenarios also have {name:create_order,stage:r50} (that step's hold phase), with QPS computed over the step duration
  const rows = [];
  const totalSec = data.state.testRunDurationMs / 1000;
  for (const key of Object.keys(data.metrics).sort()) {
    const m = /^http_req_duration\{name:([^,}]+)(,stage:([^}]+))?\}$/.exec(key);
    if (!m) continue;
    const name = m[3] ? `${m[1]}{${m[3]}}` : m[1];
    const tagPart = m[3] ? `name:${m[1]},stage:${m[3]}` : `name:${m[1]}`;
    const durSec = m[3] && opts.stageSeconds ? opts.stageSeconds : totalSec;
    const d = data.metrics[key].values;
    const reqs = data.metrics[`http_reqs{${tagPart}}`];
    const failed = data.metrics[`http_req_failed{${tagPart}}`];
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

export function makeHandleSummary(scenarioName, opts = {}) {
  return function (data) {
    const runId = __ENV.RUN_ID || `${scenarioName}-${Date.now()}`;
    const dir = `${__ENV.RESULT_DIR || 'perf/results'}/${runId}`;
    const rows = rowsByName(data, opts);
    const checks = data.metrics.checks ? data.metrics.checks.values.rate * 100 : 0;
    let md = `# Load test result: ${scenarioName}\n\n`;
    md += `- Run ID: ${runId}\n- Duration: ${(data.state.testRunDurationMs / 1000).toFixed(1)}s\n`;
    md += `- Params: BASE_URL=${__ENV.BASE_URL || '-'} MODE=${__ENV.MODE || 'gateway'} VUS=${__ENV.VUS || '-'} DURATION=${__ENV.DURATION || '-'} RATE=${__ENV.RATE || '-'}\n`;
    md += `- Business check pass rate (checks): ${checks.toFixed(2)}%\n\n`;
    md += '| Endpoint | Requests | QPS | P50 (ms) | P90 (ms) | P99 (ms) | HTTP error rate |\n|---|---|---|---|---|---|---|\n';
    for (const r of rows) {
      md += `| ${r.name} | ${r.count} | ${fmt(r.qps)} | ${fmt(r.p50)} | ${fmt(r.p90)} | ${fmt(r.p99)} | ${r.errRate.toFixed(2)}% |\n`;
    }
    const biz = Object.keys(data.metrics).filter((k) => /^biz_[a-z_]+\{/.test(k)).sort();
    if (biz.length) {
      md += '\n| Business result | Count |\n|---|---|\n';
      for (const k of biz) {
        const c = data.metrics[k].values.count;
        if (c > 0) md += `| ${k} | ${c} |\n`;
      }
    }
    if (data.metrics.dropped_iterations) {
      md += `\n- Dropped iterations: ${data.metrics.dropped_iterations.values.count} (in arrival-rate mode, running out of VUs = the system can't keep up)\n`;
    }
    md += '\n> HTTP error rate counts only 4xx/5xx and timeouts; rate-limit 429s count too (except in S1, which declares 429 as expected). Business failures (HTTP 200 but code != 200, e.g. out of stock) show up in checks.\n';
    return {
      stdout: textSummary(data, { indent: ' ', enableColors: true }) + '\n\n' + md,
      [`${dir}/summary.json`]: JSON.stringify(data, null, 2),
      [`${dir}/summary.md`]: md,
    };
  };
}

/** Declare an always-true threshold per endpoint, only so k6 emits per-name sub-metrics */
export function perEndpointThresholds(names) {
  const t = {};
  for (const n of names) {
    t[`http_req_duration{name:${n}}`] = ['max>=0'];
    t[`http_reqs{name:${n}}`] = ['count>=0'];
    t[`http_req_failed{name:${n}}`] = ['rate>=0'];
  }
  return t;
}

/** Declare every tag combination of the business counters so they show up in the summary (same purpose as perEndpointThresholds) */
export function bizThresholds(metric, tagSets) {
  const t = {};
  for (const tags of tagSets) {
    const inner = Object.entries(tags).map(([k, v]) => `${k}:${v}`).join(',');
    t[`${metric}{${inner}}`] = ['count>=0'];
  }
  return t;
}
