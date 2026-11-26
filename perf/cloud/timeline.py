#!/usr/bin/env python3
"""Slice k6's per-request CSV (--out csv) into N-second buckets and print completed / QPS / errors / P50 / P99 per bucket.
Scale-out and failure drills need "how it changes over time"; k6's summary is a whole-run average that hides turning points and recovery time.
  python3 perf/cloud/timeline.py perf/results/<RUN_ID>/raw.csv.gz [bucket seconds=5]
Error = HTTP status not 2xx/429, or timeout (status 0); 429 is a by-design rate-limit rejection and counted separately.
Times are Pacific time, to line up with ECS events."""
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
print(f"{'Time(PT)':<10}{'Done':>6}{'QPS':>8}{'Errors':>7}{'429':>5}{'P50ms':>9}{'P99ms':>9}")
for t in sorted(buckets):
    d, err, lim = buckets[t]
    ts = datetime.fromtimestamp(t, tz).strftime('%H:%M:%S')
    print(f"{ts:<10}{len(d):>6}{len(d)/step:>8.1f}{err:>7}{lim:>5}{pct(d,.5):>9.0f}{pct(d,.99):>9.0f}")
