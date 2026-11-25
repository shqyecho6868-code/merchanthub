# 一台 EC2 用 docker compose 跑 Nacos、RocketMQ（NameServer + Broker）、Elasticsearch。
# 这三个 AWS 没有"开箱即用又便宜"的托管版（Amazon MQ 不支持 RocketMQ；OpenSearch 要单独收费），学习项目合并到一台机器
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "middleware" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.middleware_instance_type
  subnet_id              = aws_subnet.private[0].id
  vpc_security_group_ids = [aws_security_group.middleware.id]
  iam_instance_profile   = aws_iam_instance_profile.middleware.name

  # 只允许 IMDSv2（要先拿 token 才能读元数据），防 SSRF 读走实例凭证 —— 行业通行的加固项
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 30
    volume_type           = "gp3"
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/middleware-init.sh.tftpl", {
    region          = var.region
    log_group       = aws_cloudwatch_log_group.middleware.name
    compose_file    = file("${path.module}/middleware-compose.yml")
    compose_version = "v5.6.1"
  })
  # user_data 只在首次启动执行一次；内容变了要重建机器才生效
  user_data_replace_on_change = true

  # 镜像都要从公网拉，必须等 NAT 通了再启动，否则 user_data 里 docker pull 全失败
  depends_on = [aws_nat_gateway.main, aws_route_table_association.private]

  tags = { Name = "${var.name}-middleware" }
}
