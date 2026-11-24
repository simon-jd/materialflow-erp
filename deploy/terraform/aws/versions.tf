terraform {
  # 1.10 adds the S3 backend's native lockfile (use_lockfile), so no DynamoDB lock table is needed.
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Partial configuration: supply the bucket with `terraform init -backend-config=backend.hcl`.
  # See backend.hcl.example and docs/runbooks/aws-deploy.md for the one-time state bucket bootstrap.
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region

  # Refuse to touch any account other than the one named in the tfvars file.
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}
