# Offline tests of modules/ecs-service with a mocked AWS provider.

mock_provider "aws" {
  # Policy documents are rendered by the provider, so the mock returns a minimal valid policy.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }

  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
}

variables {
  name                          = "erp-core"
  name_prefix                   = "materialflow-dev"
  region                        = "us-east-1"
  account_id                    = "123456789012"
  cluster_arn                   = "arn:aws:ecs:us-east-1:123456789012:cluster/materialflow-dev"
  image                         = "123456789012.dkr.ecr.us-east-1.amazonaws.com/materialflow-dev/erp-core:0123abc"
  ecr_repository_arns           = ["arn:aws:ecr:us-east-1:123456789012:repository/materialflow-dev/erp-core"]
  cpu                           = 1024
  memory                        = 2048
  desired_count                 = 1
  container_port                = 8080
  environment                   = { ZETA = "last", ALPHA = "first" }
  secrets                       = { MATERIALFLOW_DB_PASSWORD = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds-AbCdEf:password::" }
  secret_arns                   = ["arn:aws:secretsmanager:us-east-1:123456789012:secret:rds-AbCdEf"]
  kms_key_arn                   = "arn:aws:kms:us-east-1:123456789012:key/example"
  health_check_command          = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1"]
  otel_collector_image          = "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0"
  otel_collector_config         = "receivers: {}"
  subnet_ids                    = ["subnet-a", "subnet-b"]
  security_group_ids            = ["sg-erp-core"]
  service_connect_namespace_arn = "arn:aws:servicediscovery:us-east-1:123456789012:namespace/ns-example"
  service_connect_alias         = "erp-core"
  log_retention_days            = 14
}

run "task_runs_on_graviton_within_its_memory_budget" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  assert {
    condition     = aws_ecs_task_definition.this.runtime_platform[0].cpu_architecture == "ARM64"
    error_message = "Tasks must run on Graviton (ARM64)."
  }

  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.name] == ["otel-collector", "erp-core"]
    error_message = "Expected the collector sidecar followed by the application container."
  }

  assert {
    condition     = one([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.memory if c.name == "erp-core"]) == 1792
    error_message = "The JVM container limit must be task memory minus the 256 MiB sidecar reserve."
  }

  assert {
    condition     = one([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.readonlyRootFilesystem && c.mountPoints[0].containerPath == "/tmp" if c.name == "erp-core"])
    error_message = "The application container must be read-only with a writable /tmp volume."
  }

  assert {
    condition     = one([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : [for e in c.environment : e.name] if c.name == "erp-core"]) == ["ALPHA", "ZETA"]
    error_message = "Environment variables must be rendered in a stable (sorted) order to avoid spurious revisions."
  }

  assert {
    condition     = one([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.essential if c.name == "otel-collector"]) == false
    error_message = "Losing the trace collector must not stop the task."
  }
}

run "service_rolls_forward_safely_and_stays_private" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  assert {
    condition     = aws_ecs_service.this.deployment_circuit_breaker[0].enable && aws_ecs_service.this.deployment_circuit_breaker[0].rollback
    error_message = "Deployments must roll back automatically when tasks never become healthy."
  }

  assert {
    condition     = aws_ecs_service.this.deployment_minimum_healthy_percent == 100 && aws_ecs_service.this.deployment_maximum_percent == 200
    error_message = "A replacement task must start before the old one stops."
  }

  assert {
    condition     = aws_ecs_service.this.network_configuration[0].assign_public_ip == false
    error_message = "Tasks must not get public IPs."
  }

  assert {
    condition     = aws_ecs_service.this.service_connect_configuration[0].service[0].client_alias[0].dns_name == "erp-core"
    error_message = "Other services reach erp-core through its Service Connect alias."
  }

  assert {
    condition     = aws_iam_role.task.name == "materialflow-dev-erp-core-task" && aws_iam_role.execution.name == "materialflow-dev-erp-core-execution"
    error_message = "Role names must match the ARNs the deploy role may pass (github.tf)."
  }
}

run "init_container_must_succeed_before_the_application_starts" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  variables {
    init_container = {
      name    = "create-topics"
      image   = "123456789012.dkr.ecr.us-east-1.amazonaws.com/materialflow-dev/kafka-tools:4.1.2"
      command = ["/bin/sh", "-ec", "true"]
    }
  }

  assert {
    condition     = contains(one([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.dependsOn if c.name == "erp-core"]), { containerName = "create-topics", condition = "SUCCESS" })
    error_message = "The application must wait for the init container to exit 0."
  }

  assert {
    condition     = sum([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.memory]) <= 2048
    error_message = "Container hard limits must fit in the task memory."
  }
}

run "without_tracing_there_is_no_collector_and_no_xray_permission" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  variables {
    enable_tracing        = false
    otel_collector_image  = null
    otel_collector_config = null
  }

  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : c.name] == ["erp-core"] && length(aws_iam_role_policy.task) == 0
    error_message = "Disabling tracing must remove the sidecar and the X-Ray policy."
  }
}

run "tracing_requires_a_collector_image" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  variables {
    otel_collector_image = null
  }

  expect_failures = [var.otel_collector_image]
}

run "rejects_tasks_too_small_for_the_sidecar_reserve" {
  command = plan

  module {
    source = "./modules/ecs-service"
  }

  variables {
    memory = 512
  }

  expect_failures = [var.memory]
}
