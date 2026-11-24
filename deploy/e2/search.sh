#!/bin/sh
# E2 演示：商家 7 按关键词搜订单（直连 order，手动带上网关会注入的身份头）
#   ./deploy/e2/search.sh T恤
curl -s -G http://127.0.0.1:8107/orders/search --data-urlencode "keyword=${1:-}" \
  -H 'X-Tenant-Id: 7' -H 'X-User-Id: 2001' -H 'X-Role: MERCHANT_ADMIN'
echo
