variable "name" {
  description = "Cluster name and name prefix."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the broker security group."
  type        = string
}

variable "subnet_ids" {
  description = "Client subnets, one broker per subnet."
  type        = list(string)
}

variable "kafka_version" {
  description = "MSK Kafka version string."
  type        = string
}

variable "broker_instance_type" {
  description = "MSK Standard broker size."
  type        = string
}

variable "broker_volume_size_gb" {
  description = "EBS volume size per broker in GiB."
  type        = number
}

variable "kms_key_arn" {
  description = "KMS key for data at rest and the broker log group."
  type        = string
}

variable "log_retention_days" {
  description = "Broker log retention in days."
  type        = number
}
