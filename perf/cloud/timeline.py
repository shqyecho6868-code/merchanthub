#!/usr/bin/env python3
"""G-下：把 k6 的逐请求 CSV（--out csv）按 N 秒切片，输出每片的 完成数 / QPS / 错误数 / P50 / P99。
扩容、故障演练要看"随时间怎么变"，k6 的汇总只有整段平均，看不出拐点和恢复时间。
  python3 perf/cloud/timeline.py perf/results/<RUN_ID>/raw.csv.gz [切片秒数=5]
错误 = HTTP 非 2xx/429 或超时（status 0）；429 是限流按设计拒绝，单独计数。时间为太平洋时间，方便和 ECS 事件对照。"""
import csv, gzip, sys
from datetime import datetime, timezone, timedelta

path = sys.argv[1]
step = int(sys.argv[2]) if len(sys.argv) > 2 else 5
buckets = {}
with gzip.open(path, 'rt') as f:
    for r in csv.DictReader(f):
        if r['metric_name'] != 'http_req_duration':
            continue
        tags = r.get('extra_tags', '') + ' ' + r.get('name', '')
        if 'create_order' not in tags:
            continue
        t = int(r['timestamp']) // step * step
        b = buckets.setdefault(t, [[], 0, 0])
        b[0].append(float(r['metric_value']))
        st = r.get('status', '')
        if st == '429':
            b[2] += 1
        elif not st.startswith('2'):
            b[1] += 1

def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else 0

tz = timezone(timedelta(hours=-8))
print(f"{'时间(PT)':<10}{'完成':>6}{'QPS':>8}{'错误':>6}{'429':>5}{'P50ms':>9}{'P99ms':>9}")
for t in sorted(buckets):
    d, err, lim = buckets[t]
    ts = datetime.fromtimestamp(t, tz).strftime('%H:%M:%S')
    print(f"{ts:<10}{len(d):>6}{len(d)/step:>8.1f}{err:>6}{lim:>5}{pct(d,.5):>9.0f}{pct(d,.99):>9.0f}")
