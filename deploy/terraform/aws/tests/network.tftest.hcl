# Offline tests of modules/network with a mocked AWS provider.

mock_provider "aws" {
  # Policy documents are rendered by the provider, so the mock returns a minimal valid policy.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }

  # Resources whose ARNs feed ARN-validated arguments get syntactically valid mock ARNs.
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:/mock" }
  }

  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
}

variables {
  name                       = "materialflow-dev"
  region                     = "us-east-1"
  account_id                 = "123456789012"
  cidr_block                 = "10.42.0.0/16"
  availability_zones         = ["us-east-1a", "us-east-1b"]
  single_nat_gateway         = true
  enable_interface_endpoints = false
  flow_log_traffic_type      = "REJECT"
  log_retention_days         = 14
  kms_key_arn                = "arn:aws:kms:us-east-1:123456789012:key/example"
}

run "one_shared_nat_gateway_by_default" {
  module {
    source = "./modules/network"
  }

  assert {
    condition     = length(aws_nat_gateway.this) == 1 && alltrue([for route in aws_route.private_nat : route.nat_gateway_id == aws_nat_gateway.this[0].id])
    error_message = "Both private route tables must use the single NAT gateway."
  }

  assert {
    condition     = length(aws_vpc_endpoint.interface) == 0 && aws_vpc_endpoint.s3.vpc_endpoint_type == "Gateway"
    error_message = "Only the free S3 gateway endpoint exists by default."
  }

  assert {
    condition     = [for subnet in aws_subnet.private : subnet.cidr_block] == ["10.42.128.0/20", "10.42.144.0/20"] && [for subnet in aws_subnet.public : subnet.cidr_block] == ["10.42.0.0/20", "10.42.16.0/20"]
    error_message = "Unexpected subnet layout."
  }

  assert {
    condition     = alltrue([for subnet in aws_subnet.public : subnet.map_public_ip_on_launch != true])
    error_message = "Public subnets must not hand out public IPs implicitly."
  }
}

run "per_az_nat_gateways_on_request" {
  module {
    source = "./modules/network"
  }

  variables {
    single_nat_gateway = false
  }

  assert {
    condition     = length(aws_nat_gateway.this) == 2 && aws_route.private_nat[1].nat_gateway_id == aws_nat_gateway.this[1].id
    error_message = "Each AZ must route through its own NAT gateway."
  }
}

run "interface_endpoints_on_request" {
  module {
    source = "./modules/network"
  }

  variables {
    enable_interface_endpoints = true
  }

  assert {
    condition     = toset(keys(aws_vpc_endpoint.interface)) == toset(["ecr.api", "ecr.dkr", "logs", "secretsmanager"])
    error_message = "Expected ECR, CloudWatch Logs and Secrets Manager endpoints."
  }
}

run "requires_two_availability_zones" {
  command = plan

  module {
    source = "./modules/network"
  }

  variables {
    availability_zones = ["us-east-1a"]
  }

  expect_failures = [var.availability_zones]
}
