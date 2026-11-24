output "public_url" {
  description = "CloudFront URL serving the admin console, /api/*, /idp/* and /actuator/health."
  value       = local.public_base_url
}

output "oidc_issuer_uri" {
  description = "Issuer in every access token (and OIDC_ISSUER_URI of the resource servers)."
  value       = local.oidc_issuer_uri
}

output "alb_dns_name" {
  description = "ALB DNS name. Direct requests get 403: only CloudFront sends the origin header."
  value       = module.edge.alb_dns_name
}

output "ecr_repository_urls" {
  description = "ECR repository URI by image name."
  value       = module.ecr.repository_urls
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "ecs_services_created" {
  description = "False until bootstrap_image_tag is set (see the bootstrap steps in docs/runbooks/aws-deploy.md)."
  value       = var.bootstrap_image_tag != null
}

output "database_master_secret_arn" {
  description = "RDS-managed master credential secret (rotated by RDS; see the runbook)."
  value       = module.postgres.master_user_secret_arn
}

output "github_oidc_subject" {
  description = "OIDC subject the deploy role trusts; the repository's subject template must produce it."
  value       = module.github_deploy_role.oidc_subject
}

output "github_actions_variables" {
  description = "Repository variables the cd-aws workflow reads (set them with gh variable set)."
  value = {
    AWS_DEPLOY_ROLE_ARN        = module.github_deploy_role.role_arn
    AWS_REGION                 = var.aws_region
    AWS_RESOURCE_PREFIX        = local.name
    CONSOLE_BUCKET             = module.edge.console_bucket_name
    CLOUDFRONT_DISTRIBUTION_ID = module.edge.cloudfront_distribution_id
    PUBLIC_BASE_URL            = local.public_base_url
  }
}
