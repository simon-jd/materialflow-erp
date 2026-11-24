locals {
  ecs_arn_prefix = "arn:${local.partition}:ecs:${var.aws_region}:${var.aws_account_id}"
  iam_arn_prefix = "arn:${local.partition}:iam::${var.aws_account_id}"
}

# The deploy role is created before the ECS services (they need images it pushes first), so it
# names services and task roles by their deterministic ARNs instead of referencing them. The
# role names follow modules/ecs-service: <prefix>-<service>-task and -execution.
module "github_deploy_role" {
  source = "./modules/github-deploy-role"

  name                 = "${local.name}-github-deploy"
  github_repository    = var.github_repository
  github_environment   = var.github_environment
  github_branch        = var.github_branch
  create_oidc_provider = var.create_github_oidc_provider

  ecr_repository_arns = values(module.ecr.repository_arns)
  ecs_service_arns    = [for service in local.ecs_services : "${local.ecs_arn_prefix}:service/${local.name}/${service}"]
  task_role_arns = flatten([
    for service in local.ecs_services : [
      "${local.iam_arn_prefix}:role/${local.name}-${service}-task",
      "${local.iam_arn_prefix}:role/${local.name}-${service}-execution",
    ]
  ])
  console_bucket_arn          = module.edge.console_bucket_arn
  cloudfront_distribution_arn = module.edge.cloudfront_distribution_arn
}
