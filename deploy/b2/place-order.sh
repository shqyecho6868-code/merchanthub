#!/bin/sh
# 下一单：买家 1001 买 1 件 SKU 1（商家 7；C2 起请求体必须带 tenantId）。下单接口会一直等到本地事务 + 消息提交完才返回
curl -s -X POST http://127.0.0.1:8107/orders \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1001' -H 'X-Role: BUYER' \
  -d '{"tenantId":7,"items":[{"skuId":1,"quantity":1}]}'
echo
