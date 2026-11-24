output "repository_urls" {
  description = "Repository URI by repository name."
  value       = { for key, repo in aws_ecr_repository.this : key => repo.repository_url }
}

output "repository_arns" {
  description = "Repository ARN by repository name."
  value       = { for key, repo in aws_ecr_repository.this : key => repo.arn }
}
