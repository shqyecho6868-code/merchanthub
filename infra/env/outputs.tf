output "alb_url" {
  description = "对外入口：HTTP 接口走 <alb_url>/api/..."
  value       = "http://${aws_lb.main.dns_name}"
}

output "websocket_url" {
  value = "ws://${aws_lb.main.dns_name}:9101"
}

output "middleware_instance_id" {
  description = "中间件 EC2：aws ssm start-session --target <id> 登录（不需要 SSH 密钥）"
  value       = aws_instance.middleware.id
}

output "rds_endpoint" {
  value = aws_db_instance.mysql.address
}

output "assets_bucket" {
  value = aws_s3_bucket.assets.bucket
}

output "dashboard_url" {
  value = "https://${var.region}.console.aws.amazon.com/cloudwatch/home?region=${var.region}#dashboards/dashboard/${var.name}"
}

output "region" {
  value = var.region
}
