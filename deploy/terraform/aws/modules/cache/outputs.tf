output "primary_endpoint_address" {
  description = "Primary endpoint host name."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "Valkey port."
  value       = aws_elasticache_replication_group.this.port
}

output "auth_secret_arn" {
  description = "Secrets Manager secret holding the AUTH token (plain string)."
  value       = aws_secretsmanager_secret.auth.arn
}

output "security_group_id" {
  description = "Security group attached to the cache."
  value       = aws_security_group.this.id
}
