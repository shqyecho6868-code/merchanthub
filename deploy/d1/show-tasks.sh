#!/bin/sh
# Show how product 1001's current approval looks in the runtime and history tables
#   ./deploy/d1/show-tasks.sh            -> latest round of product 1001
cd "$(dirname "$0")/../.." || exit 1
. deploy/b2/env.sh
q() { docker exec mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t -e "$1" 2>/dev/null; }
PID=$(docker exec mh-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT process_instance_id FROM mh_user.listing_approval WHERE product_id = 1001 ORDER BY audit_round DESC LIMIT 1" 2>/dev/null)
echo "== Product (mh_product.product)"
q "SELECT id, tenant_id, name, status, audit_round, audit_instance_id, reject_reason FROM mh_product.product WHERE id = 1001"
[ -n "$PID" ] || { echo "(no approval instance yet)"; exit 0; }
echo "== Process instance $PID"
echo "== ACT_RU_TASK (runtime: who it is waiting on now)"
q "SELECT t.ID_, t.NAME_, t.TASK_DEF_KEY_, l.GROUP_ID_ AS candidate_group, t.CREATE_TIME_
   FROM mh_user.ACT_RU_TASK t LEFT JOIN mh_user.ACT_RU_IDENTITYLINK l ON l.TASK_ID_ = t.ID_ AND l.TYPE_ = 'candidate'
   WHERE t.PROC_INST_ID_ = '$PID'"
echo "== ACT_HI_TASKINST (history: every task that happened)"
q "SELECT ID_, NAME_, TASK_DEF_KEY_, START_TIME_, END_TIME_, DELETE_REASON_ FROM mh_user.ACT_HI_TASKINST
   WHERE PROC_INST_ID_ = '$PID' ORDER BY START_TIME_"
echo "== Approval record / pending messages"
q "SELECT status, reject_reason FROM mh_user.listing_approval WHERE process_instance_id = '$PID'"
q "SELECT id, tag, status, sent_at FROM mh_user.approval_outbox WHERE payload->>'$.processInstanceId' = '$PID'"
