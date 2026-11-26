# ---------- ECR：每个服务一个镜像仓库 ----------
locals {
  # key = ECS 服务名 = Nacos 里的服务名（gateway 例外，Nacos 里叫 GateWay，但没有其他服务调用它）
  services = {
    "gateway"           = { module = "GateWay", port = 10010, extra_ports = [] }
    "user-tenant"       = { module = "UserService", port = 8104, extra_ports = [] }
    "product-inventory" = { module = "ProductInventoryService", port = 8106, extra_ports = [] }
    "order"             = { module = "OrderService", port = 8107, extra_ports = [] }
    "payment"           = { module = "PaymentService", port = 8108, extra_ports = [] }
    # realtime：8102 是 Spring 的 HTTP（actuator 健康检查），9101 是 Netty WebSocket（客户端连这个）
    "realtime" = { module = "RealTimeService", port = 8102, extra_ports = [9101] }
  }

  mw_ip = aws_instance.middleware.private_ip

  # 所有服务共用的环境变量：application-aws.yml 里的 ${...} 就是从这里来的
  common_env = {
    SPRING_PROFILES_ACTIVE = "prod,aws"
    JAVA_OPTS              = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
    AWS_REGION             = var.region
    MYSQL_HOST             = aws_db_instance.mysql.address
    MYSQL_USERNAME         = aws_db_instance.mysql.username
    REDIS_HOST             = aws_elasticache_cluster.redis.cache_nodes[0].address
    NACOS_ADDR             = "${local.mw_ip}:8848"
    ROCKETMQ_NAMESRV       = "${local.mw_ip}:9876"
    ES_URIS                = "http://${local.mw_ip}:9200"
    S3_BUCKET              = aws_s3_bucket.assets.bucket
    # G-下：压测商家 901 的下单限流提额（默认每商家 20 QPS，C2），否则压到的是限流器而不是下单链路。
    # 与 F1 本地压测的 tenant-overrides[901]=100000 一致；只有 order 读这个配置，其他服务忽略
    MERCHANTHUB_ORDER_SENTINEL_RATELIMIT_TENANTOVERRIDES_901 = "100000"
  }
}

resource "aws_ecr_repository" "svc" {
  for_each = local.services
  name     = "${var.name}/${each.key}"
  # tag 不可变：同一个 git sha 只能对应一个镜像，回滚时"部署 abc123"一定是当时那份代码
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # destroy 时连镜像一起删，否则仓库非空删不掉（还会按 GB 收存储费）
}

resource "aws_ecr_lifecycle_policy" "svc" {
  for_each   = aws_ecr_repository.svc
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "只保留最近 5 个镜像"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 5 }
      action       = { type = "expire" }
    }]
  })
}

# ---------- ECS 集群 ----------
resource "aws_ecs_cluster" "main" {
  name = var.name
  # Container Insights：每个 task 的 CPU / 内存 / 网络指标进 CloudWatch（ECS 默认只有服务级的 CPU / 内存）
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = local.services
  name              = "/${var.name}/ecs/${each.key}"
  retention_in_days = 1 # 学习环境：日志留 1 天，destroy 时也会删
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # Fargate 只支持 awsvpc：每个 task 一块独立网卡、一个 VPC 私网 IP
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64" # CI 在 x86 runner 上构建；ARM（Graviton）便宜约 20%，但要多架构镜像，暂不做
  }

  container_definitions = jsonencode([{
    name         = each.key
    image        = "${aws_ecr_repository.svc[each.key].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [for p in concat([each.value.port], each.value.extra_ports) : { containerPort = p, protocol = "tcp" }]
    environment  = [for k, v in local.common_env : { name = k, value = v }]
    secrets = [
      { name = "MYSQL_PASSWORD", valueFrom = aws_ssm_parameter.mysql_password.arn },
      { name = "JWT_SECRET", valueFrom = aws_ssm_parameter.jwt_secret.arn },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.svc[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
    # 停止前给 30 秒优雅下线：Spring 收到 SIGTERM 后先从 Nacos 注销、处理完在途请求
    stopTimeout = 30
  }])
}

resource "aws_ecs_service" "svc" {
  for_each               = local.services
  name                   = each.key
  cluster                = aws_ecs_cluster.main.id
  task_definition        = aws_ecs_task_definition.svc[each.key].arn
  desired_count          = 1
  launch_type            = "FARGATE"
  enable_execute_command = true

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false # 私有子网，出网走 NAT
  }

  # 新版本起不来（比如连不上数据库一直重启）时自动回滚到上一个能跑的版本
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = contains(keys(local.alb_targets), each.key) ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.public[each.key].arn
      container_name   = each.key
      container_port   = local.alb_targets[each.key].port
    }
  }
  # Spring Boot 在 0.5 vCPU 上冷启动要一两分钟，这段时间 ALB 健康检查失败不算数，免得还没起完就被杀掉
  health_check_grace_period_seconds = contains(keys(local.alb_targets), each.key) ? 240 : null

  lifecycle {
    # 镜像版本由 CI/CD 注册新的 task 定义修订号来更新，Terraform 不要把它改回去
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http, aws_lb_listener.ws, aws_iam_role_policy.ecs_execution_ssm]
}
