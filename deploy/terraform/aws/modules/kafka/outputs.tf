output "bootstrap_brokers_tls" {
  description = "Comma-separated TLS bootstrap broker list (port 9094)."
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}

output "security_group_id" {
  description = "Security group attached to the brokers."
  value       = aws_security_group.this.id
}
