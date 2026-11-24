data "aws_partition" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  name      = "${var.project}-${var.environment}"
  partition = data.aws_partition.current.partition
  azs       = slice(sort(data.aws_availability_zones.available.names), 0, 2)

  app_services = ["erp-core", "event-relay", "edge-gateway", "stream-projection", "integration-adapter"]
  ecs_services = concat(local.app_services, ["keycloak"])

  # Our images (tagged with the git SHA by cd-aws) plus a mirror of the Kafka CLI image used to
  # create topics, so no task depends on Docker Hub availability or rate limits.
  repositories = concat(local.ecs_services, ["kafka-tools"])
}

# One customer-managed key for every encrypted resource in the environment.
data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "AccountAdministersKeyThroughIam"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${var.aws_account_id}:root"]
    }
  }

  statement {
    sid = "CloudWatchLogsEncryptsOwnLogGroups"
    actions = [
      "kms:Decrypt*",
      "kms:Describe*",
      "kms:Encrypt*",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${local.partition}:logs:${var.aws_region}:${var.aws_account_id}:log-group:*"]
    }
  }
}

resource "aws_kms_key" "main" {
  description             = "${local.name}: RDS, MSK, ElastiCache, Secrets Manager and CloudWatch Logs"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.main.key_id
}

module "network" {
  source = "./modules/network"

  name                       = local.name
  region                     = var.aws_region
  account_id                 = var.aws_account_id
  cidr_block                 = var.vpc_cidr
  availability_zones         = local.azs
  single_nat_gateway         = var.single_nat_gateway
  enable_interface_endpoints = var.enable_interface_endpoints
  flow_log_traffic_type      = var.flow_log_traffic_type
  log_retention_days         = var.log_retention_days
  kms_key_arn                = aws_kms_key.main.arn
}

module "ecr" {
  source = "./modules/ecr"

  name         = local.name
  repositories = toset(local.repositories)
  force_delete = var.force_destroy_artifacts
}
