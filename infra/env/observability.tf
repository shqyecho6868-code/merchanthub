# Logs: one log group per service (ecs.tf), one shared by the middleware containers. Metrics: Container Insights + built-in ALB metrics, collected on one dashboard
resource "aws_cloudwatch_log_group" "middleware" {
  name              = "/${var.name}/middleware"
  retention_in_days = 1
}

# Dashboard: the key lines for the order path on one page, used during cloud load tests (the first 3 dashboards are free)
resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = var.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB requests / 5xx"
          region = var.region
          stat   = "Sum"
          period = 60
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.main.arn_suffix],
            [".", "HTTPCode_Target_5XX_Count", ".", "."],
            [".", "HTTPCode_ELB_5XX_Count", ".", "."]
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title   = "ALB response time P99 (s)"
          region  = var.region
          stat    = "p99"
          period  = 60
          metrics = [["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix]]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title   = "CPU per service (%)"
          region  = var.region
          stat    = "Average"
          period  = 60
          metrics = [for s in keys(local.services) : ["AWS/ECS", "CPUUtilization", "ClusterName", var.name, "ServiceName", s]]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title   = "Memory per service (%)"
          region  = var.region
          stat    = "Average"
          period  = 60
          metrics = [for s in keys(local.services) : ["AWS/ECS", "MemoryUtilization", "ClusterName", var.name, "ServiceName", s]]
        }
      },
      # Safety-net numbers pushed by order, plus the alarm line
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title  = "Outbox backlog (pending / older than 5m) and expired pending orders"
          region = var.region
          stat   = "Maximum"
          period = 60
          metrics = [
            [local.app_metric_namespace, "OutboxPending"],
            [".", "OutboxStalePending"],
            [".", "ExpiredPendingOrders"]
          ]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title   = "Outbox fast-path drops per minute (pool full, left to the scan)"
          region  = var.region
          stat    = "Sum"
          period  = 60
          metrics = [[local.app_metric_namespace, "OutboxFastPathDropped"]]
        }
      },
      {
        type = "alarm", x = 0, y = 18, width = 24, height = 3
        properties = {
          title  = "Safety-net alarms"
          alarms = [aws_cloudwatch_metric_alarm.outbox_stale.arn, aws_cloudwatch_metric_alarm.expired_pending_orders.arn]
        }
      }
    ]
  })
}
