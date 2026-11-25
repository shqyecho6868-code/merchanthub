# G-上 云上环境：所有收费资源都在这里。terraform apply 一条命令创建，terraform destroy 一条命令全删。
# state 存本地（infra/env/terraform.tfstate，不提交）：单人学习项目、当天建当天删，不需要 S3 远程 state + 锁
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8.0"
    }
  }
}

provider "aws" {
  region = var.region
  # 所有资源自动带这几个标签：控制台 / 账单里按 Project=merchanthub 一筛就能确认有没有残留
  default_tags {
    tags = { Project = "merchanthub", Stack = "env", ManagedBy = "terraform" }
  }
}
