# ALB：唯一的公网入口。80 → gateway（所有 HTTP 接口），9101 → realtime（WebSocket 订单推送）。
# 不做域名和 HTTPS（任务范围外）：直接用 ALB 自带的 DNS 名访问
locals {
  alb_targets = {
    "gateway"  = { port = 10010, health_port = "traffic-port" }
    "realtime" = { port = 9101, health_port = "8102" } # Netty 端口不认 HTTP 健康检查，改打 Spring 的 8102
  }
}

resource "aws_lb" "main" {
  name               = var.name
  load_balancer_type = "application"
  internal           = false
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "public" {
  for_each    = local.alb_targets
  name        = "${var.name}-${each.key}"
  port        = each.value.port
  protocol    = "HTTP"
  target_type = "ip" # Fargate 的 awsvpc 网络：目标是 task 的私网 IP，不是 EC2 实例
  vpc_id      = aws_vpc.main.id

  health_check {
    # readiness 探针：只看本进程能不能接流量，不聚合 MySQL / Redis 健康（见 application-aws.yml）
    path                = "/actuator/health/readiness"
    port                = each.value.health_port
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # 摘除旧 task 时只等 15 秒在途请求（默认 300 秒，滚动发布会很慢）
  deregistration_delay = 15
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.public["gateway"].arn
  }
}

# gateway 自己的 /actuator（health、prometheus）不对公网开放。ALB 健康检查直接打 task IP，不经过监听器规则，不受影响
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10
  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      status_code  = "404"
      message_body = "Not Found"
    }
  }
}

resource "aws_lb_listener" "ws" {
  load_balancer_arn = aws_lb.main.arn
  port              = 9101
  protocol          = "HTTP" # ALB 原生支持 WebSocket：HTTP 监听器收到 Upgrade 请求后保持长连接
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.public["realtime"].arn
  }
}
