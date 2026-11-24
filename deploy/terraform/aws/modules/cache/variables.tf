variable "name" {
  description = "Replication group ID and name prefix."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the cache security group."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets for the cache subnet group."
  type        = list(string)
}

variable "engine_version" {
  description = "Valkey engine version."
  type        = string
}

variable "node_type" {
  description = "ElastiCache node type."
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key for data at rest and the AUTH token secret."
  type        = string
}
