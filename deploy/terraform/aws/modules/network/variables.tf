variable "name" {
  description = "Name prefix for every network resource."
  type        = string
}

variable "region" {
  description = "AWS region, used to build VPC endpoint service names."
  type        = string
}

variable "account_id" {
  description = "AWS account ID, used to scope the flow-log delivery role trust."
  type        = string
}

variable "cidr_block" {
  description = "VPC CIDR block."
  type        = string
}

variable "availability_zones" {
  description = "Availability zones to spread public and private subnets across."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "At least two availability zones are required (RDS subnet groups and the ALB need two)."
  }
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway across all private subnets."
  type        = bool
}

variable "enable_interface_endpoints" {
  description = "Create interface VPC endpoints for interface_endpoint_services."
  type        = bool
}

variable "interface_endpoint_services" {
  description = "AWS service suffixes to create interface endpoints for."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager"]
}

variable "flow_log_traffic_type" {
  description = "VPC flow log traffic filter."
  type        = string
}

variable "log_retention_days" {
  description = "Flow log retention in days."
  type        = number
}

variable "kms_key_arn" {
  description = "KMS key that encrypts the flow log group."
  type        = string
}
