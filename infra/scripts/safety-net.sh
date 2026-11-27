#!/bin/bash
# Is the safety net running in the cloud? One screen: XXL-Job admin boot, registered executors, latest run of each job,
# outbox backlog, and the two CloudWatch alarms.
#   ./infra/scripts/safety-net.sh
# Expected when healthy: XXL_ADMIN_STARTED; order / product-inventory / payment / user-tenant each list an address;
# jobs 100 / 101 / 120 have recent runs with handle_code 200; pending outbox small, stale 0; both alarms OK
set -uo pipefail
cd "$(dirname "$0")/../.."
REGION=$(terraform -chdir=infra/env output -raw region)

./perf/cloud/remote.sh 'echo "== XXL-Job admin boot (/var/log/xxl-init.log)"; tail -2 /var/log/xxl-init.log
echo "== Registered executors"
$MYSQL -t -e "SELECT app_name, address_list FROM xxl_job.xxl_job_group ORDER BY id"
echo "== Latest run per job (UTC)"
$MYSQL -t -e "SELECT l.job_id, i.executor_handler, l.trigger_time, l.handle_code, LEFT(l.handle_msg, 60) handle_msg
  FROM xxl_job.xxl_job_log l JOIN xxl_job.xxl_job_info i ON i.id = l.job_id
  WHERE l.id IN (SELECT MAX(id) FROM xxl_job.xxl_job_log GROUP BY job_id) ORDER BY l.job_id"
echo "== Outbox backlog"
$MYSQL -t -e "SELECT status, COUNT(*) n, SUM(created_at < UTC_TIMESTAMP(3) - INTERVAL 5 MINUTE) older_than_5m, MIN(created_at) oldest
  FROM mh_order.order_outbox GROUP BY status"'

echo "== CloudWatch alarms"
aws cloudwatch describe-alarms --region "$REGION" --alarm-name-prefix merchanthub- \
  --query 'MetricAlarms[].[AlarmName, StateValue, StateUpdatedTimestamp]' --output text
