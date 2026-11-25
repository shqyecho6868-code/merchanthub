variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  description = "资源名前缀；ECS 集群、ECR 仓库、角色名都用它（CI 部署角色的权限按这个前缀授权）"
  type        = string
  default     = "merchanthub"
}

variable "allowed_cidrs" {
  description = "允许访问 ALB 的来源。默认全网；建议 apply 时换成自己的公网 IP，如 [\"1.2.3.4/32\"]"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "middleware_instance_type" {
  description = "跑 Nacos + RocketMQ + ES 的 EC2。8GB 是下限（ES 1G 堆 + Broker 1G 堆 + Nacos + NameServer + 系统）；m7i-flex.large 在 Free plan 允许的规格里"
  type        = string
  default     = "m7i-flex.large"
}

variable "image_tag" {
  description = "首次创建 task 定义用的镜像 tag。之后由 CI/CD 注册新版本（git sha），Terraform 不再管"
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
