#!/bin/sh
# D1 🧑 实验：看商品 1001 这次审批在运行时表、历史表里的样子
#   ./deploy/d1/show-tasks.sh            → 商品 1001 最新一轮
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
q() { docker exec mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t -e "$1" 2>/dev/null; }
PID=$(docker exec mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT process_instance_id FROM mh_user.listing_approval WHERE product_id = 1001 ORDER BY audit_round DESC LIMIT 1" 2>/dev/null)
echo "== 商品（mh_product.product）"
q "SELECT id, tenant_id, name, status, audit_round, audit_instance_id, reject_reason FROM mh_product.product WHERE id = 1001"
[ -n "$PID" ] || { echo "（还没有审批实例）"; exit 0; }
echo "== 流程实例 $PID"
echo "== ACT_RU_TASK（运行时：现在等谁处理）"
q "SELECT t.ID_, t.NAME_, t.TASK_DEF_KEY_, l.GROUP_ID_ AS candidate_group, t.CREATE_TIME_
   FROM mh_user.ACT_RU_TASK t LEFT JOIN mh_user.ACT_RU_IDENTITYLINK l ON l.TASK_ID_ = t.ID_ AND l.TYPE_ = 'candidate'
   WHERE t.PROC_INST_ID_ = '$PID'"
echo "== ACT_HI_TASKINST（历史：发生过的每个任务）"
q "SELECT ID_, NAME_, TASK_DEF_KEY_, START_TIME_, END_TIME_, DELETE_REASON_ FROM mh_user.ACT_HI_TASKINST
   WHERE PROC_INST_ID_ = '$PID' ORDER BY START_TIME_"
echo "== 审批单 / 待发消息"
q "SELECT status, reject_reason FROM mh_user.listing_approval WHERE process_instance_id = '$PID'"
q "SELECT id, tag, status, sent_at FROM mh_user.approval_outbox WHERE payload->>'$.processInstanceId' = '$PID'"
