resource "aws_security_group" "this" {
  name        = "${var.name}-valkey"
  description = "ElastiCache Valkey; ingress rules are granted per client in the root module"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-valkey" }
}

resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

# AUTH token for the default user. Spring reads it as SPRING_DATA_REDIS_PASSWORD from
# Secrets Manager; the value also lives in Terraform state, which is encrypted in S3.
resource "random_password" "auth" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "auth" {
  name_prefix             = "${var.name}/valkey-auth-"
  description             = "AUTH token for the ${var.name} Valkey replication group"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "auth" {
  secret_id     = aws_secretsmanager_secret.auth.id
  secret_string = random_password.auth.result
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Gateway rate limits and erp-core cache for ${var.name}"
  engine               = "valkey"
  engine_version       = var.engine_version
  node_type            = var.node_type
  num_cache_clusters   = 1
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  # Spring Boot's Lettuce client speaks TLS with spring.data.redis.ssl.enabled=true; the
  # ElastiCache certificate chains to a public root already in the JRE trust store.
  transit_encryption_enabled = true
  auth_token                 = random_password.auth.result
  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn

  snapshot_retention_limit = 1
  apply_immediately        = true
}
