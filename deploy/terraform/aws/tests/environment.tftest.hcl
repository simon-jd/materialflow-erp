# Offline tests: every provider is mocked, so `terraform test` needs no AWS account or
# credentials and makes no API calls. They exercise the root wiring with realistic values.

mock_provider "aws" {
  # Policy documents are rendered by the provider, so the mock returns a minimal valid policy.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }

  mock_data "aws_availability_zones" {
    defaults = { names = ["us-east-1c", "us-east-1a", "us-east-1b"] }
  }

  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }

  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      domain_name = "d111111abcdef8.cloudfront.net"
      arn         = "arn:aws:cloudfront::123456789012:distribution/EDFDVBD6EXAMPLE"
    }
  }

  # Resources whose ARNs feed ARN-validated arguments get syntactically valid mock ARNs.
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000" }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:us-east-1:123456789012:log-group:/mock" }
  }

  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }

  mock_resource "aws_msk_configuration" {
    defaults = { arn = "arn:aws:kafka:us-east-1:123456789012:configuration/mock/00000000-0000-0000-0000-000000000000-1", latest_revision = 1 }
  }

  mock_resource "aws_cloudfront_function" {
    defaults = { arn = "arn:aws:cloudfront::123456789012:function/mock" }
  }

  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/mock/0123456789abcdef" }
  }

  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/mock/0123456789abcdef/0123456789abcdef" }
  }

  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/mock/0123456789abcdef" }
  }

  mock_resource "aws_ecs_cluster" {
    defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:cluster/materialflow-dev" }
  }

  mock_resource "aws_service_discovery_http_namespace" {
    defaults = { arn = "arn:aws:servicediscovery:us-east-1:123456789012:namespace/ns-mock" }
  }

  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:us-east-1:123456789012:secret:mock-AbCdEf" }
  }

  mock_resource "aws_ecr_repository" {
    defaults = { arn = "arn:aws:ecr:us-east-1:123456789012:repository/mock" }
  }

  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }

  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock-bucket" }
  }

  mock_resource "aws_db_instance" {
    defaults = {
      address = "materialflow-dev.example.us-east-1.rds.amazonaws.com"
      port    = 5432
      db_name = "materialflow"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-example-AbCdEf"
        kms_key_id    = "arn:aws:kms:us-east-1:123456789012:key/example"
        secret_status = "active"
      }]
    }
  }

  mock_resource "aws_msk_cluster" {
    defaults = { bootstrap_brokers_tls = "b-1.example.kafka.us-east-1.amazonaws.com:9094,b-2.example.kafka.us-east-1.amazonaws.com:9094" }
  }

  mock_resource "aws_elasticache_replication_group" {
    defaults = { primary_endpoint_address = "master.example.cache.amazonaws.com", port = 6379 }
  }
}

mock_provider "random" {
  # ElastiCache validates the AUTH token's character set.
  mock_resource "random_password" {
    defaults = { result = "MockedAuthToken0123456789abcdefMockedAuthToken01" }
  }
}

variables {
  enable_aws     = true
  aws_account_id = "123456789012"
}

run "plans_fail_without_explicit_opt_in" {
  command = plan

  variables {
    enable_aws = false
  }

  expect_failures = [var.enable_aws]
}

run "rejects_a_malformed_account_id" {
  command = plan

  variables {
    aws_account_id = "12345"
  }

  expect_failures = [var.aws_account_id]
}

run "first_apply_creates_infrastructure_but_no_services" {
  assert {
    condition     = length(module.service) == 0 && length(module.keycloak) == 0 && !output.ecs_services_created
    error_message = "ECS services must wait for bootstrap_image_tag, i.e. for images to exist."
  }

  assert {
    condition = toset(keys(module.ecr.repository_urls)) == toset([
      "erp-core", "event-relay", "edge-gateway", "stream-projection", "integration-adapter", "keycloak", "kafka-tools",
    ])
    error_message = "Expected one ECR repository per image."
  }

  assert {
    condition     = output.github_actions_variables.AWS_RESOURCE_PREFIX == "materialflow-dev" && output.github_actions_variables.AWS_REGION == "us-east-1"
    error_message = "Workflow variables must expose the resource prefix and region."
  }
}

run "bootstrap_image_tag_creates_every_service" {
  variables {
    bootstrap_image_tag = "0123456789abcdef0123456789abcdef01234567"
  }

  assert {
    condition     = toset(keys(module.service)) == toset(["erp-core", "event-relay", "edge-gateway", "stream-projection", "integration-adapter"]) && length(module.keycloak) == 1
    error_message = "Expected five application services plus Keycloak."
  }

  assert {
    condition     = alltrue([for name, service in module.service : service.task_definition_family == "materialflow-dev-${name}"])
    error_message = "Task families must follow <prefix>-<service>; the deploy role and cd-aws rely on it."
  }

  assert {
    condition     = output.oidc_issuer_uri == "https://d111111abcdef8.cloudfront.net/idp/realms/materialflow"
    error_message = "The issuer must be the public CloudFront URL Keycloak advertises."
  }

  assert {
    condition     = output.public_url == output.github_actions_variables.PUBLIC_BASE_URL
    error_message = "The smoke test must target the public URL."
  }
}

run "deploy_role_trusts_only_main_in_the_aws_dev_environment" {
  assert {
    condition     = output.github_oidc_subject == "repo:simon-jd/materialflow-erp:environment:aws-dev:ref:refs/heads/main"
    error_message = "Unexpected OIDC subject: ${output.github_oidc_subject}"
  }
}

run "network_flows_are_symmetric_and_least_privilege" {
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.flow) == 15 && length(aws_vpc_security_group_egress_rule.flow) == 15
    error_message = "Every flow needs exactly one ingress and one egress rule."
  }

  assert {
    condition = alltrue([
      for key, rule in aws_vpc_security_group_ingress_rule.flow :
      rule.from_port == rule.to_port && rule.referenced_security_group_id != null && rule.cidr_ipv4 == null
    ])
    error_message = "East-west rules must name one port and a source security group, never a CIDR."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.alb_from_cloudfront.from_port == 80 && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.prefix_list_id != null
    error_message = "The ALB must only accept CloudFront's origin-facing prefix list."
  }

  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_egress_rule.service_https : rule.from_port == 443 && rule.to_port == 443])
    error_message = "Internet egress from tasks is limited to HTTPS."
  }
}

run "rejects_environment_names_that_overflow_aws_name_limits" {
  command = plan

  variables {
    environment = "integration-test"
  }

  expect_failures = [var.environment]
}
