variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  description = "Resource name prefix used by the ECS cluster, ECR repos and role names (the CI deploy role's permissions are scoped by this prefix)"
  type        = string
  default     = "merchanthub"
}

variable "allowed_cidrs" {
  description = "Sources allowed to reach the ALB. Defaults to everywhere; better to set your own public IP at apply time, e.g. [\"1.2.3.4/32\"]"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "middleware_instance_type" {
  description = "EC2 running Nacos + RocketMQ + ES. 8 GB is the minimum (ES 1G heap + Broker 1G heap + Nacos + NameServer + OS); m7i-flex.large is allowed on the Free plan"
  type        = string
  default     = "m7i-flex.large"
}

variable "image_tag" {
  description = "Image tag for the first task definition. After that CI/CD registers new revisions (git sha) and Terraform no longer manages it"
  type        = string
  default     = "initial"
}

variable "task_cpu" {
  type    = number
  default = 512
}

variable "task_memory" {
  type    = number
  default = 1024
}
