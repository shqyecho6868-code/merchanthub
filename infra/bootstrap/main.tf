# G-上 bootstrap：长期保留、本身不收费的资源，只 apply 一次，平时不 destroy。
#   1. AWS Budget 账单告警（最先建：哪怕后面忘了 destroy，花费超阈值也会收到邮件）
#   2. GitHub Actions 的 OIDC 身份提供商 + 部署角色（CI 用临时凭证，仓库和 Secrets 里都没有长期 Access Key）
# 收费资源全在 ../env，那个才是"一条命令创建、一条命令销毁"的环境。
# 分两个 state 的原因：env 每天 destroy，如果部署角色也在 env 里，环境没起时 CI 连"环境不在、跳过部署"都判断不了
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "merchanthub", Stack = "bootstrap", ManagedBy = "terraform" }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "alert_email" {
  description = "账单告警邮箱（填在 terraform.tfvars，不提交）"
  type        = string
}

variable "monthly_budget_usd" {
  description = "月预算（美元）。学习项目每次开几小时、当天 destroy，正常一个月几美元；超过 20 说明有资源忘删了"
  type        = string
  default     = "20"
}

variable "github_repo" {
  description = "允许部署的仓库（owner/name）"
  type        = string
  default     = "shqyecho6868-code/merchanthub"
}

data "aws_caller_identity" "current" {}

# ---------- 1. 账单告警 ----------
resource "aws_budgets_budget" "monthly" {
  name         = "merchanthub-monthly"
  budget_type  = "COST"
  limit_amount = var.monthly_budget_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 关键：不扣 credits。Free plan 账号的花费会先被 credits 抵扣，默认口径下"实际花费"一直是 0，告警永远不响
  cost_types {
    include_credit = false
    include_refund = false
  }

  # 实际花费到 50% / 80% / 100% 各发一次；再加一个"按当前速度预测月底会超"的提前告警
  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.alert_email]
    }
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}

# ---------- 2. GitHub Actions OIDC ----------
# CI 运行时向 GitHub 要一个 OIDC token，拿它找 AWS STS 换 1 小时有效的临时凭证。
# 比"把 IAM 用户的 Access Key 放进 GitHub Secrets"安全：没有长期密钥可泄露，且只有本仓库 main 分支能换到
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github_deploy" {
  name = "merchanthub-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        # 只认 main 分支：PR、其他分支、fork 都换不到凭证
        StringLike = { "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:ref:refs/heads/main" }
      }
    }]
  })
}

# 最小权限：只能推 merchanthub/* 镜像、更新 merchanthub 集群的服务，不能建删任何基础设施
resource "aws_iam_role_policy" "github_deploy" {
  name = "deploy"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload", "ecr:DescribeImages",
          "ecr:DescribeRepositories", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart"
        ]
        Resource = "arn:aws:ecr:${var.region}:${data.aws_caller_identity.current.account_id}:repository/merchanthub/*"
      },
      # Describe / RegisterTaskDefinition 不支持按资源限定，只能 *
      {
        Effect   = "Allow"
        Action   = ["ecs:DescribeClusters", "ecs:DescribeServices", "ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition", "ecs:TagResource"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/merchanthub/*"
      },
      # 注册新版本 task 定义时要把 task 用的两个角色"交给" ECS
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/merchanthub-ecs-*"
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}

output "github_deploy_role_arn" {
  description = "填到 GitHub 仓库 Settings → Secrets → Actions 的 AWS_DEPLOY_ROLE_ARN"
  value       = aws_iam_role.github_deploy.arn
}
