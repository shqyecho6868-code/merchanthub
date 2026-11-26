#!/bin/sh
# Demo: merchant 7 searches orders by keyword (hits order directly, passing the identity headers the gateway would inject)
#   ./deploy/e2/search.sh T-shirt
curl -s -G http://127.0.0.1:8107/orders/search --data-urlencode "keyword=${1:-}" \
  -H 'X-Tenant-Id: 7' -H 'X-User-Id: 2001' -H 'X-Role: MERCHANT_ADMIN'
echo
