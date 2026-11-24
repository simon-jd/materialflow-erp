variable "name" {
  description = "Service and application container name."
  type        = string
}

variable "name_prefix" {
  description = "Environment prefix for the task family, roles and log group."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (task role confused-deputy conditions)."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster ARN."
  type        = string
}

variable "image" {
  description = "Application image URI including tag."
  type        = string
}

variable "ecr_repository_arn" {
  description = "ECR repository the execution role may pull from."
  type        = string
}

variable "cpu" {
  description = "Task CPU units."
  type        = number
}

variable "memory" {
  description = "Task memory in MiB; 256 MiB is reserved for the collector, init container and Service Connect proxy."
  type        = number

  validation {
    condition     = var.memory >= 1024
    error_message = "Tasks need at least 1024 MiB: the JVM keeps memory minus the 256 MiB sidecar reserve."
  }
}

variable "desired_count" {
  description = "Number of running tasks."
  type        = number
}

variable "container_port" {
  description = "Application HTTP port (port mapping name \"http\")."
  type        = number
}

variable "extra_port_mappings" {
  description = "Additional container ports, e.g. a management or gRPC port."
  type = list(object({
    name         = string
    port         = number
    app_protocol = string
  }))
  default = []
}

variable "environment" {
  description = "Plain environment variables for the application container."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables injected from Secrets Manager: name => valueFrom (secret ARN with optional JSON key)."
  type        = map(string)
  default     = {}
}

variable "secret_arns" {
  description = "Secrets the execution role may read (every secret referenced by secrets and the init container)."
  type        = list(string)
  default     = []
}

variable "kms_key_arn" {
  description = "KMS key encrypting the log group and the referenced secrets."
  type        = string
}

variable "readonly_root_filesystem" {
  description = "Run the application container with a read-only root filesystem and a writable /tmp volume."
  type        = bool
  default     = true
}

variable "health_check_command" {
  description = "Container health check command (ECS replaces unhealthy tasks); null to rely on the load balancer."
  type        = list(string)
  default     = null
}

variable "init_container" {
  description = "Optional one-shot container that must exit 0 before the application starts."
  type = object({
    name        = string
    image       = string
    command     = list(string)
    environment = optional(map(string), {})
    secrets     = optional(map(string), {})
  })
  default = null
}

variable "enable_tracing" {
  description = "Run the OpenTelemetry collector sidecar that forwards the application's OTLP traces to X-Ray."
  type        = bool
  default     = true
}

variable "otel_collector_image" {
  description = "AWS Distro for OpenTelemetry collector image (required when enable_tracing)."
  type        = string
  default     = null

  validation {
    condition     = !var.enable_tracing || var.otel_collector_image != null
    error_message = "otel_collector_image is required when enable_tracing is true."
  }
}

variable "otel_collector_config" {
  description = "Collector configuration YAML passed as AOT_CONFIG_CONTENT (required when enable_tracing)."
  type        = string
  default     = null

  validation {
    condition     = !var.enable_tracing || var.otel_collector_config != null
    error_message = "otel_collector_config is required when enable_tracing is true."
  }
}

variable "subnet_ids" {
  description = "Private subnets for the tasks."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups attached to the tasks."
  type        = list(string)
}

variable "load_balancer" {
  description = "Optional target group registration."
  type = object({
    target_group_arn = string
    container_port   = number
  })
  default = null
}

variable "health_check_grace_period_seconds" {
  description = "Seconds the load balancer health checks are ignored after a task starts."
  type        = number
  default     = 180
}

variable "service_connect_namespace_arn" {
  description = "Cloud Map namespace for ECS Service Connect; null disables Service Connect."
  type        = string
  default     = null
}

variable "service_connect_alias" {
  description = "DNS name other services use to reach this one on container_port; null makes the task a client only."
  type        = string
  default     = null
}

variable "log_retention_days" {
  description = "Log retention in days."
  type        = number
}
