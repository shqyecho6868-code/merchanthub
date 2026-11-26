# Bootstrap: long-lived resources that cost nothing by themselves; applied once and normally never destroyed.
#   1. AWS Budget alerts (created first: even if a later destroy is forgotten, an email arrives once spend crosses the threshold)
#   2. GitHub Actions OIDC identity provider + deploy role (CI uses temporary credentials; no long-lived access key in the repo or its secrets)
# All billable resources live in ../env, the environment that is created and destroyed with a single command each.
# Why two states: env is destroyed daily; if the deploy role lived there too, CI couldn't even tell "environment is down, skip deploy" while it's down
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
  description = "Email for billing alerts (set in terraform.tfvars, not committed)"
  type        = string
}

variable "monthly_budget_usd" {
  description = "Monthly budget (USD). The environment runs a few hours at a time and is destroyed the same day, normally a few dollars a month; over 20 means something was left running"
  type        = string
  default     = "20"
}

# GitHub has "immutable subject" enabled for this repo: the OIDC token's sub carries the numeric owner / repo IDs,
# like repo:owner@<ownerId>/name@<repoId>:ref:..., instead of repo:owner/name:ref:...
# Benefit: if the repo is renamed or deleted and someone recreates one with the same name, its ID differs and it can't obtain credentials.
# Look it up with: gh api repos/<owner>/<name>/actions/oidc/customization/sub --jq .sub_claim_prefix
variable "github_sub_prefix" {
  description = "OIDC token sub prefix of the repo allowed to deploy"
  type        = string
  default     = "repo:shqyecho6868-code@267807370/merchanthub@1460433640"
}

data "aws_caller_identity" "current" {}

# ---------- 1. Billing alerts ----------
resource "aws_budgets_budget" "monthly" {
  name         = "merchanthub-monthly"
  budget_type  = "COST"
  limit_amount = var.monthly_budget_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Key point: don't net out credits. On a Free plan account spend is first covered by credits, so by default "actual spend" stays 0 and the alert never fires
  cost_types {
    include_credit = false
    include_refund = false
  }

  # One alert each at 50% / 80% / 100% of actual spend, plus an early alert when the forecast says the month will go over
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
# At run time CI asks GitHub for an OIDC token and exchanges it with AWS STS for 1-hour temporary credentials.
# Safer than putting an IAM user's access key in GitHub Secrets: no long-lived key to leak, and only this repo's main branch can get credentials
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
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          # main branch only: PRs, other branches and forks can't get credentials
          "token.actions.githubusercontent.com:sub" = "${var.github_sub_prefix}:ref:refs/heads/main"
        }
      }
    }]
  })
}

# Least privilege: may only push merchanthub/* images and update services in the merchanthub cluster; can't create or delete any infrastructure
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
      # Describe / RegisterTaskDefinition don't support resource-level scoping, so *
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
      # Registering a new task-definition revision requires "passing" the task's two roles to ECS
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
  description = "Put this in the GitHub repo's Settings -> Secrets -> Actions as AWS_DEPLOY_ROLE_ARN"
  value       = aws_iam_role.github_deploy.arn
}
