output "role_arn" {
  description = "Role ARN for the AWS_DEPLOY_ROLE_ARN repository variable."
  value       = aws_iam_role.this.arn
}

output "oidc_subject" {
  description = "Exact OIDC subject claim the trust policy accepts."
  value       = local.subject
}
