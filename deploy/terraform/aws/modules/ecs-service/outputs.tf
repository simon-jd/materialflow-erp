output "service_name" {
  description = "ECS service name."
  value       = aws_ecs_service.this.name
}

output "task_definition_family" {
  description = "Task definition family the cd-aws workflow renders new revisions from."
  value       = aws_ecs_task_definition.this.family
}

output "log_group_name" {
  description = "Application log group."
  value       = aws_cloudwatch_log_group.this.name
}
