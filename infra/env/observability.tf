# 日志：6 个服务各一个日志组（ecs.tf），中间件容器共用一个；指标：Container Insights + ALB 自带指标，汇总到一个看板
resource "aws_cloudwatch_log_group" "middleware" {
  name              = "/${var.name}/middleware"
  retention_in_days = 1
}

# 看板：下单链路最关心的几条线放一页。G-下云上压测时直接看这个（前 3 个看板免费）
resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = var.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB 请求数 / 5xx"
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
          title   = "ALB 响应时间 P99（秒）"
          region  = var.region
          stat    = "p99"
          period  = 60
          metrics = [["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix]]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title   = "各服务 CPU（%）"
          region  = var.region
          stat    = "Average"
          period  = 60
          metrics = [for s in keys(local.services) : ["AWS/ECS", "CPUUtilization", "ClusterName", var.name, "ServiceName", s]]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title   = "各服务内存（%）"
          region  = var.region
          stat    = "Average"
          period  = 60
          metrics = [for s in keys(local.services) : ["AWS/ECS", "MemoryUtilization", "ClusterName", var.name, "ServiceName", s]]
        }
      }
    ]
  })
}
