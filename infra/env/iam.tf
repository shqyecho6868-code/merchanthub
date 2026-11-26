# ECS 里有两个角色，分工不同（常被问）：
#   execution role：ECS 代理用 —— 拉 ECR 镜像、写 CloudWatch 日志、启动前从 SSM 读密码注入环境变量
#   task role：容器里的业务代码用 —— product-inventory 签 S3 上传 URL、HeadObject；ECS Exec 进容器排错
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
      Resource = [aws_ssm_parameter.mysql_password.arn, aws_ssm_parameter.jwt_secret.arn]
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
      # 预签名 URL 用 task role 的身份签：签名者必须自己有 PutObject 权限，URL 才有效。只给 products/ 前缀
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.assets.arn}/products/*"
      },
      # HeadObject 对象不存在时，没有 ListBucket 权限 S3 返回 403 而不是 404，代码就分不清"没上传"和"没权限"
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = aws_s3_bucket.assets.arn
        Condition = { StringLike = { "s3:prefix" = ["products/*"] } }
      },
      # ECS Exec（aws ecs execute-command 进容器排错）
      {
        Effect   = "Allow"
        Action   = ["ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel"]
        Resource = "*"
      }
    ]
  })
}

# 中间件 EC2：SSM 托管（Session Manager 登录、send-command 灌种子数据）+ 容器日志写 CloudWatch + 读种子 SQL 和数据库密码
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
      # G-下：云内压测把 k6 结果传回 S3，本机再下载（perf/cloud/run-in-vpc.sh）
      { Effect = "Allow", Action = ["s3:PutObject"], Resource = "${aws_s3_bucket.assets.arn}/ops/results/*" },
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = aws_ssm_parameter.mysql_password.arn }
    ]
  })
}

resource "aws_iam_instance_profile" "middleware" {
  name = "${var.name}-middleware"
  role = aws_iam_role.middleware.name
}
