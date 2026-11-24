variable "name" {
  description = "Instance identifier and name prefix."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the database security group."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets (at least two AZs) for the DB subnet group."
  type        = list(string)
}

variable "engine_version" {
  description = "PostgreSQL engine version."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "multi_az" {
  description = "Create a Multi-AZ standby."
  type        = bool
}

variable "deletion_protection" {
  description = "Block instance deletion."
  type        = bool
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot on deletion."
  type        = bool
}

variable "database_name" {
  description = "Initial database created with the instance."
  type        = string
}

variable "master_username" {
  description = "Master user name; the password is generated and stored by RDS."
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key for storage, Performance Insights, the master secret and the log group."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the exported PostgreSQL log group."
  type        = number
}
