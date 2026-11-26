# ALB: the only public entry point. 80 -> gateway (all HTTP APIs), 9101 -> realtime (WebSocket order push).
# No domain or HTTPS (out of scope): access via the ALB's own DNS name
locals {
  alb_targets = {
    "gateway"  = { port = 10010, health_port = "traffic-port" }
    "realtime" = { port = 9101, health_port = "8102" } # the Netty port doesn't speak HTTP health checks, so probe Spring's 8102
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
  target_type = "ip" # Fargate awsvpc networking: targets are task private IPs, not EC2 instances
  vpc_id      = aws_vpc.main.id

  health_check {
    # Readiness probe: only whether this process can take traffic, without aggregating MySQL / Redis health (see application-aws.yml)
    path                = "/actuator/health/readiness"
    port                = each.value.health_port
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Wait only 15 seconds for in-flight requests when deregistering old tasks (the default 300 makes rolling deploys slow)
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

# The gateway's own /actuator (health, prometheus) isn't exposed publicly. ALB health checks hit task IPs directly, bypassing listener rules, so they're unaffected
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
  protocol          = "HTTP" # ALB supports WebSocket natively: an HTTP listener keeps the connection open after an Upgrade request
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.public["realtime"].arn
  }
}
