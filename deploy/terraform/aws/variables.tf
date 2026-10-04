variable "enable_aws" {
  description = "Safety switch. Keep false unless a separately authorized paid-cloud deployment is intended."
  type        = bool
  default     = false
}

variable "aws_region" {
  type    = string
  default = "us-west-2"
}

variable "environment" {
  type    = string
  default = "sandbox"
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}
