#!/bin/sh
# Place one order: buyer 1001 buys 1 unit of SKU 1 (merchant 7; the request body must include tenantId). The API returns only after the local transaction + message are committed
curl -s -X POST http://127.0.0.1:8107/orders \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1001' -H 'X-Role: BUYER' \
  -d '{"tenantId":7,"items":[{"skuId":1,"quantity":1}]}'
echo
