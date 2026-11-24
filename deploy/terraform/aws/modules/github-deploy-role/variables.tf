variable "name" {
  description = "IAM role name."
  type        = string
}

variable "github_repository" {
  description = "Repository allowed to assume the role (owner/name)."
  type        = string
}

variable "github_environment" {
  description = "GitHub environment the job must reference."
  type        = string
}

variable "github_branch" {
  description = "Branch the job must run from."
  type        = string
}

variable "create_oidc_provider" {
  description = "Create the GitHub OIDC provider instead of looking up an existing one."
  type        = bool
}

variable "ecr_repository_arns" {
  description = "Repositories the workflow may push to."
  type        = list(string)
}

variable "ecs_service_arns" {
  description = "Services the workflow may update."
  type        = list(string)
}

variable "task_role_arns" {
  description = "Task and execution roles the workflow may pass in new task definition revisions."
  type        = list(string)
}

variable "console_bucket_arn" {
  description = "Bucket the workflow syncs the admin console build to."
  type        = string
}

variable "cloudfront_distribution_arn" {
  description = "Distribution the workflow may invalidate."
  type        = string
}
