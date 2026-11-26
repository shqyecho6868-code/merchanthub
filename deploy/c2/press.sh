#!/bin/bash
# Load test: a big merchant (tenant 7) places orders at high concurrency while a small merchant (tenant 8) places a few, at the same time; summarized by HTTP status code at the end.
#   ./deploy/c2/press.sh [durationSeconds=20] [tenant7Concurrency=20] [tenant8OrdersPerSecond=2]
# Needs only curl; hits order (8107) directly with buyer identity headers (downstream services trust identity headers, the security boundary is the gateway; bypassing the gateway keeps it out of the measurement).
# Expected: lots of 429 (error code 42900) for tenant 7, all 200 for tenant 8.
cd "$(dirname "$0")/../.." || exit 1
DURATION=${1:-20}
BIG_CONCURRENCY=${2:-20}
SMALL_QPS=${3:-2}
URL=http://127.0.0.1:8107/orders
OUT=$(mktemp -d)
END=$(( $(date +%s) + DURATION ))

# One "buyer": keeps placing orders until time is up, writing one line per HTTP status code
worker() {   # $1=tenant $2=SKU $3=buyerId $4=seconds between orders (0 = no pause)
  while [ "$(date +%s)" -lt "$END" ]; do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 -X POST "$URL" \
      -H 'Content-Type: application/json' -H "X-User-Id: $3" -H 'X-Role: BUYER' \
      -d "{\"tenantId\":$1,\"items\":[{\"skuId\":$2,\"quantity\":1}]}" >> "$OUT/t$1.$3"
    [ "$4" != 0 ] && sleep "$4"
  done
}

echo "Load test ${DURATION}s: tenant 7 has ${BIG_CONCURRENCY} concurrent buyers ordering nonstop; tenant 8 places ~${SMALL_QPS} orders per second"
for i in $(seq 1 "$BIG_CONCURRENCY"); do worker 7 1 $((70000 + i)) 0 & done
worker 8 2 80001 "$(echo "scale=2; 1/$SMALL_QPS" | bc)" &
wait

summary() {   # $1=tenant
  local total ok limited other
  total=$(cat "$OUT"/t$1.* | wc -l | tr -d ' ')
  ok=$(cat "$OUT"/t$1.* | grep -c '^200$')
  limited=$(cat "$OUT"/t$1.* | grep -c '^429$')
  other=$((total - ok - limited))
  local rate=0
  [ "$total" -gt 0 ] && rate=$((ok * 100 / total))
  printf 'Tenant %s: %5d orders | 200 ok %5d | 429 rate-limited %5d | other %d | pass rate %d%%\n' \
    "$1" "$total" "$ok" "$limited" "$other" "$rate"
  [ "$other" -gt 0 ] && echo "  Other status codes: $(cat "$OUT"/t$1.* | grep -v '^200$\|^429$' | sort | uniq -c | tr '\n' ' ')"
}
echo "------------------------------------------------------------"
summary 7
summary 8
rm -rf "$OUT"
