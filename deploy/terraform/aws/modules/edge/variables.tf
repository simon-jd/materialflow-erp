variable "name" {
  description = "Name prefix (also the ALB name; at most 23 characters so target group names fit in 32)."
  type        = string

  validation {
    condition     = length(var.name) <= 23
    error_message = "name must be at most 23 characters (target group names are limited to 32)."
  }
}

variable "vpc_id" {
  description = "VPC for the target groups."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets for the ALB."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group for the ALB (admits only CloudFront origin-facing ranges)."
  type        = string
}

variable "origin_verify_secret" {
  description = "Shared secret CloudFront sends as X-Origin-Verify and the ALB requires on every forwarding rule."
  type        = string
  sensitive   = true
}

variable "console_bucket_name" {
  description = "Globally unique name of the private admin console bucket."
  type        = string
}

variable "force_destroy" {
  description = "Allow deleting the console bucket while it still holds objects."
  type        = bool
}

variable "price_class" {
  description = "CloudFront price class."
  type        = string
}
