# Consumed only by its own validation block, which is the safety gate.
# tflint-ignore: terraform_unused_declarations
variable "enable_aws" {
  description = "Explicit opt-in for billable AWS resources. Every plan fails until a tfvars file sets this to true."
  type        = bool
  default     = false

  validation {
    condition     = var.enable_aws
    error_message = "This configuration creates billable AWS resources (roughly $9/day in us-east-1; see docs/runbooks/aws-deploy.md). Set enable_aws = true in your tfvars file to acknowledge that."
  }
}

variable "aws_account_id" {
  description = "The only AWS account this configuration may modify (passed to the provider's allowed_account_ids)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be a 12-digit AWS account ID."
  }
}

variable "aws_region" {
  description = "Region for every regional resource. The cost estimate in the runbook assumes us-east-1."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Project name used in resource names and tags."
  type        = string
  default     = "materialflow"
}

variable "environment" {
  description = "Environment name used in resource names and tags."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,9}$", var.environment))
    error_message = "environment must be 2-10 lowercase letters, digits or hyphens (it is part of load balancer and target group names, which are limited to 32 characters)."
  }
}

# --- Network -----------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC CIDR block; split into two public and two private /20 subnets."
  type        = string
  default     = "10.42.0.0/16"
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (cheaper; private egress depends on one AZ). Set false for one NAT gateway per AZ."
  type        = bool
  default     = true
}

variable "enable_interface_endpoints" {
  description = "Create interface endpoints for ECR, CloudWatch Logs and Secrets Manager. They cost more than the NAT traffic they save at demo volume; the free S3 gateway endpoint is always created."
  type        = bool
  default     = false
}

variable "flow_log_traffic_type" {
  description = "VPC flow log filter: REJECT keeps ingest small and still shows blocked connections; ALL for full forensics."
  type        = string
  default     = "REJECT"

  validation {
    condition     = contains(["ACCEPT", "REJECT", "ALL"], var.flow_log_traffic_type)
    error_message = "flow_log_traffic_type must be ACCEPT, REJECT or ALL."
  }
}

variable "log_retention_days" {
  description = "Retention for every CloudWatch log group this configuration creates."
  type        = number
  default     = 14
}

# --- Data stores -------------------------------------------------------------

variable "postgres_engine_version" {
  description = "RDS PostgreSQL engine version. A major version alone lets RDS pick (and auto-upgrade) the minor version."
  type        = string
  default     = "17"
}

variable "postgres_instance_class" {
  description = "RDS instance class (Graviton burstable by default)."
  type        = string
  default     = "db.t4g.micro"
}

variable "postgres_multi_az" {
  description = "Run a synchronous standby in a second AZ (doubles the instance cost)."
  type        = bool
  default     = false
}

variable "postgres_deletion_protection" {
  description = "Block deletion of the database. Must be set to false and applied before terraform destroy."
  type        = bool
  default     = true
}

variable "postgres_skip_final_snapshot" {
  description = "Skip the final snapshot on destroy. Keep false to retain a recoverable copy."
  type        = bool
  default     = false
}

variable "kafka_version" {
  description = "Amazon MSK Kafka version. 4.1.x matches the 4.1.2 broker and clients the repository is qualified against."
  type        = string
  default     = "4.1.x"
}

variable "kafka_broker_instance_type" {
  description = "MSK Standard broker size; kafka.t3.small is the smallest and is meant for development."
  type        = string
  default     = "kafka.t3.small"
}

variable "kafka_broker_volume_size_gb" {
  description = "EBS storage per MSK broker in GiB."
  type        = number
  default     = 10
}

variable "valkey_engine_version" {
  description = "ElastiCache Valkey engine version (Redis OSS protocol compatible)."
  type        = string
  default     = "8.1"
}

variable "valkey_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.micro"
}

# --- Compute -----------------------------------------------------------------

variable "bootstrap_image_tag" {
  description = "Image tag (a git SHA pushed by the cd-aws workflow) the ECS services are first created with. Leave null on the first apply: services are only created once their images exist. After that, cd-aws owns which image runs."
  type        = string
  default     = null
}

variable "kafka_tools_image_tag" {
  description = "Tag of the apache/kafka image the cd-aws workflow mirrors into the kafka-tools repository (keep equal to KAFKA_TOOLS_VERSION in .github/workflows/cd-aws.yml)."
  type        = string
  default     = "4.1.2"
}

variable "service_overrides" {
  description = "Per-service overrides of task cpu (units), memory (MiB) and desired_count, keyed by service name."
  type = map(object({
    cpu           = optional(number)
    memory        = optional(number)
    desired_count = optional(number)
  }))
  default = {}
}

variable "container_insights" {
  description = "ECS Container Insights mode: disabled, enabled or enhanced. Enabled adds CloudWatch custom-metric charges."
  type        = string
  default     = "disabled"

  validation {
    condition     = contains(["disabled", "enabled", "enhanced"], var.container_insights)
    error_message = "container_insights must be disabled, enabled or enhanced."
  }
}

variable "trace_sampling_probability" {
  description = "Spring tracing sampling probability in AWS (local stacks sample 1.0)."
  type        = number
  default     = 0.1
}

# --- Edge and delivery -------------------------------------------------------

variable "cloudfront_price_class" {
  description = "CloudFront price class; PriceClass_100 serves from North America and Europe edges only."
  type        = string
  default     = "PriceClass_100"
}

variable "force_destroy_artifacts" {
  description = "Let terraform destroy delete non-empty ECR repositories and the console bucket. Both only hold artifacts rebuildable from git."
  type        = bool
  default     = true
}

variable "github_repository" {
  description = "GitHub repository (owner/name) whose workflow may assume the deploy role."
  type        = string
  default     = "simon-jd/materialflow-erp"
}

variable "github_environment" {
  description = "GitHub environment the deploy job must run in."
  type        = string
  default     = "aws-dev"
}

variable "github_branch" {
  description = "Branch the deploy job must run from."
  type        = string
  default     = "main"
}

variable "create_github_oidc_provider" {
  description = "Create the account's GitHub OIDC provider. Set false if the account already has one (only one may exist per URL)."
  type        = bool
  default     = true
}
