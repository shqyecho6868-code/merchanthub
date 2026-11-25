# ---------- 密码：Terraform 生成，存 SSM Parameter Store（标准参数免费），ECS 启动 task 时注入环境变量 ----------
# 注意：random_password 的值也会写进本地 terraform.tfstate（所以 state 不提交）。生产用远程 state + 加密，或 RDS 托管密码（Secrets Manager）
resource "random_password" "mysql" {
  length  = 24
  special = false # 避免 JDBC URL / shell 转义问题
}

# JWT 签名密钥：网关、user-tenant、realtime 共用。代码要求标准 Base64、解码后至少 32 字节
resource "random_bytes" "jwt" {
  length = 32
}

resource "aws_ssm_parameter" "mysql_password" {
  name  = "/${var.name}/mysql-password"
  type  = "SecureString"
  value = random_password.mysql.result
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/${var.name}/jwt-secret"
  type  = "SecureString"
  value = random_bytes.jwt.base64
}

# ---------- RDS MySQL ----------
resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "mysql" {
  identifier = var.name
  engine     = "mysql"
  # 用 8.4（LTS）而不是本地的 8.0：RDS 上 8.0 已过标准支持期（2026-07），新建会被自动计入 Extended Support 按 vCPU 小时额外收费
  engine_version = "8.4"
  # 明确关掉 Extended Support：万一版本过了标准支持期，宁可创建失败也不要悄悄多收钱
  engine_lifecycle_support = "open-source-rds-extended-support-disabled"
  instance_class           = "db.t4g.micro"
  allocated_storage        = 20
  storage_type             = "gp3"
  username                 = "mhadmin"
  password                 = random_password.mysql.result
  db_subnet_group_name     = aws_db_subnet_group.main.name
  vpc_security_group_ids   = [aws_security_group.rds.id]
  publicly_accessible      = false
  multi_az                 = false
  # 学习环境当天建当天删：不留备份、不留最终快照、不开删除保护，否则 destroy 删不掉或留下收费快照
  backup_retention_period  = 0
  skip_final_snapshot      = true
  delete_automated_backups = true
  deletion_protection      = false
  apply_immediately        = true
  # 各服务用 createDatabaseIfNotExist 自己建库（mh_order、mh_payment ...），和本地一致，不在这里建
}

# ---------- ElastiCache Redis ----------
resource "aws_elasticache_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

# 单节点、不开传输加密 / AUTH：私有子网 + 安全组只放行 ECS。代价：VPC 内明文传输；生产应开 TLS + AUTH token
resource "aws_elasticache_cluster" "redis" {
  cluster_id         = var.name
  engine             = "redis"
  engine_version     = "7.1"
  node_type          = "cache.t4g.micro"
  num_cache_nodes    = 1
  port               = 6379
  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.redis.id]
  apply_immediately  = true
}

# ---------- S3：商品图片（替换 MinIO）+ 运维文件 ----------
resource "random_id" "bucket" {
  byte_length = 4
}

resource "aws_s3_bucket" "assets" {
  bucket        = "${var.name}-assets-${random_id.bucket.hex}" # 桶名全球唯一，加随机后缀
  force_destroy = true                                         # destroy 时连同里面的图片一起删，不会因为桶非空而卡住
}

# 只公开 products/ 前缀的读权限（商品图片本来就是给所有买家看的）；ops/ 下的种子 SQL 等保持私有。
# 写入只能走服务签发的预签名 URL
resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "assets" {
  bucket = aws_s3_bucket.assets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadProductImages"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.assets.arn}/products/*"
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.assets]
}

# 浏览器直传需要 CORS（本项目没有前端，用 curl 测时不需要；留着让以后的前端能直接用）
resource "aws_s3_bucket_cors_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id
  cors_rule {
    allowed_methods = ["PUT", "GET"]
    allowed_origins = ["*"]
    allowed_headers = ["*"]
    max_age_seconds = 3000
  }
}

# 种子数据：中间件 EC2 从这里取，灌进 RDS（RDS 在私有子网，本机连不上）
resource "aws_s3_object" "seed" {
  bucket = aws_s3_bucket.assets.id
  key    = "ops/seed.sql"
  source = "${path.module}/../../perf/data/seed.sql"
  etag   = filemd5("${path.module}/../../perf/data/seed.sql")
}
