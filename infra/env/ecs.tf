# ---------- ECR: one image repository per service ----------
locals {
  # key = ECS service name = Nacos service name (except gateway, registered as GateWay in Nacos, but no other service calls it)
  services = {
    "gateway"           = { module = "GateWay", port = 10010, extra_ports = [] }
    "user-tenant"       = { module = "UserService", port = 8104, extra_ports = [] }
    "product-inventory" = { module = "ProductInventoryService", port = 8106, extra_ports = [] }
    "order"             = { module = "OrderService", port = 8107, extra_ports = [] }
    "payment"           = { module = "PaymentService", port = 8108, extra_ports = [] }
    # realtime: 8102 is Spring's HTTP port (actuator health check), 9101 is Netty WebSocket (clients connect here)
    "realtime" = { module = "RealTimeService", port = 8102, extra_ports = [9101] }
  }

  mw_ip = aws_instance.middleware.private_ip

  # Env vars shared by all services: the ${...} placeholders in application-aws.yml come from here
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
    # Raise the order rate limit for load-test merchant 901 (default 20 QPS per merchant), otherwise the load test measures the limiter instead of the order path.
    # Same as the local load test's tenant-overrides[901]=100000; only order reads this setting, other services ignore it
    MERCHANTHUB_ORDER_SENTINEL_RATELIMIT_TENANTOVERRIDES_901 = "100000"
  }
}

resource "aws_ecr_repository" "svc" {
  for_each = local.services
  name     = "${var.name}/${each.key}"
  # Immutable tags: one git sha maps to exactly one image, so rolling back to "abc123" always means that exact code
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # destroy deletes the images too; otherwise a non-empty repo can't be deleted (and storage is billed per GB)
}

resource "aws_ecr_lifecycle_policy" "svc" {
  for_each   = aws_ecr_repository.svc
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the 5 most recent images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 5 }
      action       = { type = "expire" }
    }]
  })
}

# ---------- ECS cluster ----------
resource "aws_ecs_cluster" "main" {
  name = var.name
  # Container Insights: per-task CPU / memory / network metrics in CloudWatch (by default ECS only has service-level CPU / memory)
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = local.services
  name              = "/${var.name}/ecs/${each.key}"
  retention_in_days = 1 # ephemeral environment: keep logs 1 day; destroy deletes them too
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # Fargate only supports awsvpc: one ENI and one VPC private IP per task
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64" # CI builds on x86 runners; ARM (Graviton) is ~20% cheaper but needs multi-arch images, not done yet
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
    # 30 seconds of graceful shutdown: on SIGTERM Spring first deregisters from Nacos and finishes in-flight requests
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
    assign_public_ip = false # private subnet, egress via NAT
  }

  # Roll back automatically to the last working version when a new one can't start (e.g. keeps restarting because it can't reach the database)
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
  # Spring Boot needs a minute or two to cold-start on 0.5 vCPU; ALB health-check failures during that window don't count, so tasks aren't killed before they finish starting
  health_check_grace_period_seconds = contains(keys(local.alb_targets), each.key) ? 240 : null

  lifecycle {
    # The image version is updated by CI/CD registering new task-definition revisions; Terraform must not change it back
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http, aws_lb_listener.ws, aws_iam_role_policy.ecs_execution_ssm]
}
