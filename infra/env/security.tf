# Security groups are written as "who may reach me": each tier admits only the tier above, forming a one-way chain ALB -> ECS -> data/middleware
resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "ALB: public HTTP 80 (gateway) and 9101 (realtime WebSocket)"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

# In-VPC load tests (k6 on the middleware EC2, perf/cloud/run-in-vpc.sh) leave through NAT and come back in to the ALB,
# so the ALB sees the NAT's EIP as the source. Only 80 is allowed, not 9101
resource "aws_vpc_security_group_ingress_rule" "alb_http_from_nat" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "${aws_eip.nat.public_ip}/32"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "k6 load test from middleware EC2 via NAT"
}

resource "aws_vpc_security_group_ingress_rule" "alb_ws" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 9101
  to_port           = 9101
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ECS tasks: only the ALB gets in from outside (only to gateway 10010, realtime 9101 and health check 8102);
# services talk to each other (Feign, gateway -> services) within the same security group
resource "aws_security_group" "ecs" {
  name        = "${var.name}-ecs"
  description = "ECS tasks: from ALB and from each other"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "ecs_from_alb" {
  for_each                     = toset(["10010", "9101", "8102"])
  security_group_id            = aws_security_group.ecs.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ecs_self" {
  security_group_id            = aws_security_group.ecs.id
  referenced_security_group_id = aws_security_group.ecs.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "ecs_all" {
  security_group_id = aws_security_group.ecs.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# The XXL-Job admin (on the middleware EC2) calls each executor's embedded server to trigger jobs:
# order 9201 / payment 9202 / product-inventory 9203 / user-tenant 9204
resource "aws_vpc_security_group_ingress_rule" "ecs_from_xxl_admin" {
  security_group_id            = aws_security_group.ecs.id
  referenced_security_group_id = aws_security_group.middleware.id
  from_port                    = 9201
  to_port                      = 9204
  ip_protocol                  = "tcp"
  description                  = "XXL-Job admin to executors"
}

# Middleware EC2: only ECS tasks may connect. Port 22 is closed -- log in via SSM Session Manager (over the AWS API, no public IP or key pair needed)
resource "aws_security_group" "middleware" {
  name        = "${var.name}-middleware"
  description = "Nacos / RocketMQ / ES, only from ECS tasks"
  vpc_id      = aws_vpc.main.id
}

locals {
  middleware_ports = {
    nacos_http = 8848
    nacos_grpc = 9848 # client registration / subscription uses gRPC (HTTP port + 1000); missing it makes registration time out
    namesrv    = 9876
    broker     = 10911
    broker_vip = 10909
    es         = 9200
    xxl_admin  = 8090 # executors register / send callbacks to the XXL-Job admin
  }
}

resource "aws_vpc_security_group_ingress_rule" "middleware_from_ecs" {
  for_each                     = local.middleware_ports
  security_group_id            = aws_security_group.middleware.id
  referenced_security_group_id = aws_security_group.ecs.id
  from_port                    = each.value
  to_port                      = each.value
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "middleware_all" {
  security_group_id = aws_security_group.middleware.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# Data tier: RDS admits only ECS and the middleware EC2 (the latter loads seed data); Redis admits only ECS
resource "aws_security_group" "rds" {
  name        = "${var.name}-rds"
  description = "MySQL from ECS and middleware host"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "rds" {
  for_each = {
    ecs        = aws_security_group.ecs.id
    middleware = aws_security_group.middleware.id
  }
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = each.value
  from_port                    = 3306
  to_port                      = 3306
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-redis"
  description = "Redis from ECS"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = aws_security_group.ecs.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}
