# ECS has two roles with different jobs (a common interview question):
#   execution role: used by the ECS agent -- pull ECR images, write CloudWatch logs, read passwords from SSM before start and inject them as env vars
#   task role: used by the application code in the container -- product-inventory presigns S3 upload URLs, HeadObject; ECS Exec for debugging
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_ssm" {
  name = "read-secrets"
  role = aws_iam_role.ecs_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = [aws_ssm_parameter.mysql_password.arn, aws_ssm_parameter.jwt_secret.arn, aws_ssm_parameter.xxl_job_token.arn]
    }]
  })
}

resource "aws_iam_role" "ecs_task" {
  name               = "${var.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "ecs_task" {
  name = "app"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Presigned URLs are signed with the task role's identity: the signer itself must have PutObject for the URL to be valid. products/ prefix only
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.assets.arn}/products/*"
      },
      # Without ListBucket, HeadObject on a missing object returns 403 instead of 404, and the code can't tell "not uploaded" from "no permission"
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = aws_s3_bucket.assets.arn
        Condition = { StringLike = { "s3:prefix" = ["products/*"] } }
      },
      # order pushes the outbox backlog counts (OutboxBacklogMonitor -> CloudWatchBacklogSink). PutMetricData has no resource-level
      # permissions, so it is scoped by namespace instead: the app can't write into AWS/* or anyone else's metrics
      {
        Effect    = "Allow"
        Action    = ["cloudwatch:PutMetricData"]
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = local.app_metric_namespace } }
      },
      # ECS Exec (aws ecs execute-command into a container for debugging)
      {
        Effect   = "Allow"
        Action   = ["ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel"]
        Resource = "*"
      }
    ]
  })
}

# Middleware EC2: SSM-managed (Session Manager login, send-command to load seed data) + container logs to CloudWatch + read seed SQL and DB passwords
resource "aws_iam_role" "middleware" {
  name = "${var.name}-middleware"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "middleware_ssm" {
  role       = aws_iam_role.middleware.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "middleware" {
  name = "middleware"
  role = aws_iam_role.middleware.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = "${aws_cloudwatch_log_group.middleware.arn}:*"
      },
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = "${aws_s3_bucket.assets.arn}/ops/*" },
      # In-VPC load tests upload k6 results to S3 for download to the laptop (perf/cloud/run-in-vpc.sh)
      { Effect = "Allow", Action = ["s3:PutObject"], Resource = "${aws_s3_bucket.assets.arn}/ops/results/*" },
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = [aws_ssm_parameter.mysql_password.arn, aws_ssm_parameter.xxl_job_token.arn, aws_ssm_parameter.rds_endpoint.arn] }
    ]
  })
}

resource "aws_iam_instance_profile" "middleware" {
  name = "${var.name}-middleware"
  role = aws_iam_role.middleware.name
}
