# Alarms on "is the safety net actually working". The numbers are pushed by order itself
# (OutboxBacklogMonitor -> CloudWatchBacklogSink, once a minute, scheduled by Spring -- not by XXL-Job, which is what they watch).
locals {
  app_metric_namespace = "MerchantHub/Order"
}

# Pending outbox rows older than 5 minutes. With the scan running every 5s this is 0; anything above 0 means rows are not being
# delivered (scheduler down, MQ down, catch-up too slow) -- the exact state the cloud once sat in for an hour without anyone knowing.
# Missing data counts as breaching: if order stops reporting, we can no longer show the safety net works, which is itself worth an alarm.
resource "aws_cloudwatch_metric_alarm" "outbox_stale" {
  alarm_name          = "${var.name}-outbox-stale-pending"
  alarm_description   = "order_outbox has status=0 rows older than 5 minutes: outbox scan / XXL-Job / MQ not delivering. Check: infra/scripts/safety-net.sh"
  namespace           = local.app_metric_namespace
  metric_name         = "OutboxStalePending"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
}

# Orders still pending payment 5 minutes after their deadline: timeout close (scheduled message + timeout scan) isn't happening.
# Missing data is not breaching here: the outbox alarm already covers "order stopped reporting"
resource "aws_cloudwatch_metric_alarm" "expired_pending_orders" {
  alarm_name          = "${var.name}-expired-pending-orders"
  alarm_description   = "Orders past their pay deadline + 5m still pending: timeout close not happening"
  namespace           = local.app_metric_namespace
  metric_name         = "ExpiredPendingOrders"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}
