#!/bin/bash
# C2 🧑 压测：大商家（租户 7）高并发下单 + 小商家（租户 8）少量下单，同时进行，结束后按 HTTP 状态码汇总。
#   ./deploy/c2/press.sh [持续秒数=20] [租户7并发数=20] [租户8每秒单数=2]
# 只依赖 curl，直连 order（8107）并自带买家身份头（C1：下游信任身份头，安全边界在网关；压测绕过网关，排除网关干扰）。
# 期望：租户 7 大量 429（错误码 42900），租户 8 全部 200。
cd "$(dirname "$0")/../.." || exit 1
DURATION=${1:-20}
BIG_CONCURRENCY=${2:-20}
SMALL_QPS=${3:-2}
URL=http://127.0.0.1:8107/orders
OUT=$(mktemp -d)
END=$(( $(date +%s) + DURATION ))

# 一个"买家"：循环下单直到时间到，每次把 HTTP 状态码记一行
worker() {   # $1=租户 $2=SKU $3=买家ID $4=每单间隔秒（0=不停）
  while [ "$(date +%s)" -lt "$END" ]; do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 -X POST "$URL" \
      -H 'Content-Type: application/json' -H "X-User-Id: $3" -H 'X-Role: BUYER' \
      -d "{\"tenantId\":$1,\"items\":[{\"skuId\":$2,\"quantity\":1}]}" >> "$OUT/t$1.$3"
    [ "$4" != 0 ] && sleep "$4"
  done
}

echo "压测 ${DURATION}s：租户 7 并发 ${BIG_CONCURRENCY} 个买家不停下单；租户 8 每秒约 ${SMALL_QPS} 单"
for i in $(seq 1 "$BIG_CONCURRENCY"); do worker 7 1 $((70000 + i)) 0 & done
worker 8 2 80001 "$(echo "scale=2; 1/$SMALL_QPS" | bc)" &
wait

summary() {   # $1=租户
  local total ok limited other
  total=$(cat "$OUT"/t$1.* | wc -l | tr -d ' ')
  ok=$(cat "$OUT"/t$1.* | grep -c '^200$')
  limited=$(cat "$OUT"/t$1.* | grep -c '^429$')
  other=$((total - ok - limited))
  local rate=0
  [ "$total" -gt 0 ] && rate=$((ok * 100 / total))
  printf '租户 %s：共 %5d 单 | 200 成功 %5d | 429 被限流 %5d | 其他 %d | 通过率 %d%%\n' \
    "$1" "$total" "$ok" "$limited" "$other" "$rate"
  [ "$other" -gt 0 ] && echo "  其他状态码分布：$(cat "$OUT"/t$1.* | grep -v '^200$\|^429$' | sort | uniq -c | tr '\n' ' ')"
}
echo "------------------------------------------------------------"
summary 7
summary 8
rm -rf "$OUT"
