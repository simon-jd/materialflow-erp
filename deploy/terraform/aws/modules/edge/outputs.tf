output "cloudfront_domain_name" {
  description = "Public host name serving the console, /api, /idp and the gateway health check."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "cloudfront_distribution_id" {
  description = "Distribution ID (cache invalidation after console deploys)."
  value       = aws_cloudfront_distribution.this.id
}

output "cloudfront_distribution_arn" {
  description = "Distribution ARN."
  value       = aws_cloudfront_distribution.this.arn
}

output "console_bucket_name" {
  description = "Admin console bucket name."
  value       = aws_s3_bucket.console.id
}

output "console_bucket_arn" {
  description = "Admin console bucket ARN."
  value       = aws_s3_bucket.console.arn
}

output "alb_dns_name" {
  description = "ALB DNS name (only reachable through CloudFront)."
  value       = aws_lb.this.dns_name
}

# ECS rejects a target group that is not yet attached to a load balancer, so these outputs
# wait for the listener rules that reference them.
output "gateway_target_group_arn" {
  description = "Target group for edge-gateway tasks."
  value       = aws_lb_target_group.gateway.arn
  depends_on  = [aws_lb_listener_rule.gateway]
}

output "keycloak_target_group_arn" {
  description = "Target group for Keycloak tasks."
  value       = aws_lb_target_group.keycloak.arn
  depends_on  = [aws_lb_listener_rule.keycloak]
}
