# 安全组按"谁能访问我"来写：每一层只放行上一层，形成 ALB → ECS → 数据层/中间件 的单向链
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

# G-下：云内压测（k6 跑在中间件 EC2 上，perf/cloud/run-in-vpc.sh）经 NAT 出公网再访问 ALB，
# ALB 看到的来源是 NAT 的 EIP。只放行 80，不放 9101
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

# ECS task：外部只有 ALB 能进（只到 gateway 10010、realtime 9101 和健康检查 8102）；
# 服务之间（Feign、gateway → 各服务）在同一个安全组内互通
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

# 中间件 EC2：只有 ECS task 能连。没有开 22 端口 —— 登录机器用 SSM Session Manager（走 AWS API，不需要公网 IP 和密钥对）
resource "aws_security_group" "middleware" {
  name        = "${var.name}-middleware"
  description = "Nacos / RocketMQ / ES, only from ECS tasks"
  vpc_id      = aws_vpc.main.id
}

locals {
  middleware_ports = {
    nacos_http = 8848
    nacos_grpc = 9848 # 客户端注册 / 订阅走 gRPC（HTTP 端口 + 1000），漏了会注册超时
    namesrv    = 9876
    broker     = 10911
    broker_vip = 10909
    es         = 9200
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

# 数据层：RDS 只放行 ECS 和中间件 EC2（后者用来灌种子数据），Redis 只放行 ECS
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
