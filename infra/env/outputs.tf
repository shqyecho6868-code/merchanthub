output "alb_url" {
  description = "Public entry point: HTTP APIs at <alb_url>/api/..."
  value       = "http://${aws_lb.main.dns_name}"
}

output "websocket_url" {
  value = "ws://${aws_lb.main.dns_name}:9101"
}

output "middleware_instance_id" {
  description = "Middleware EC2: log in with aws ssm start-session --target <id> (no SSH key needed)"
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
