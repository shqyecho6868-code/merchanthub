# One EC2 instance runs Nacos, RocketMQ (NameServer + Broker) and Elasticsearch with docker compose.
# AWS has no cheap, ready-to-use managed version of these (Amazon MQ doesn't support RocketMQ; OpenSearch is billed separately), so they share one machine
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "middleware" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.middleware_instance_type
  subnet_id              = aws_subnet.private[0].id
  vpc_security_group_ids = [aws_security_group.middleware.id]
  iam_instance_profile   = aws_iam_instance_profile.middleware.name

  # IMDSv2 only (a token is required before reading metadata), preventing SSRF from stealing instance credentials -- standard hardening
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
  # user_data runs only on first boot; changing it requires replacing the instance
  user_data_replace_on_change = true

  # Images are pulled from the internet, so wait for NAT before starting, otherwise every docker pull in user_data fails
  depends_on = [aws_nat_gateway.main, aws_route_table_association.private]

  tags = { Name = "${var.name}-middleware" }
}
