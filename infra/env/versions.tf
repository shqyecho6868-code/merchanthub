# Cloud environment: every billable resource lives here. terraform apply creates it with one command, terraform destroy removes it all with one command.
# State is local (infra/env/terraform.tfstate, not committed): single-person project, created and destroyed the same day, so no S3 remote state + locking needed
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
  # Every resource gets these tags: filtering by Project=merchanthub in the console / billing confirms nothing is left behind
  default_tags {
    tags = { Project = "merchanthub", Stack = "env", ManagedBy = "terraform" }
  }
}
