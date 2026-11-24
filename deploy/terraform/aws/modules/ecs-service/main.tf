data "aws_partition" "current" {}

locals {
  family = "${var.name_prefix}-${var.name}"

  # Hard limit for the application container so the JVM's MaxRAMPercentage is computed from
  # its own budget. The reserve covers the collector, the one-shot init container and the
  # Service Connect proxy that ECS injects without a limit of its own.
  sidecar_memory = 128
  app_memory     = var.memory - 2 * local.sidecar_memory

  log_options = {
    "awslogs-group"   = aws_cloudwatch_log_group.this.name
    "awslogs-region"  = var.region
    "mode"            = "non-blocking"
    "max-buffer-size" = "4m"
  }

  port_mappings = concat(
    [{ name = "http", containerPort = var.container_port, protocol = "tcp", appProtocol = "http" }],
    [for p in var.extra_port_mappings : { name = p.name, containerPort = p.port, protocol = "tcp", appProtocol = p.app_protocol }],
  )

  app_container = merge(
    {
      name                   = var.name
      image                  = var.image
      essential              = true
      memory                 = local.app_memory
      portMappings           = local.port_mappings
      environment            = [for key, value in var.environment : { name = key, value = value }]
      secrets                = [for key, value in var.secrets : { name = key, valueFrom = value }]
      readonlyRootFilesystem = var.readonly_root_filesystem
      mountPoints            = var.readonly_root_filesystem ? [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }] : []
      stopTimeout            = 45
      dependsOn = concat(
        var.enable_tracing ? [{ containerName = "otel-collector", condition = "START" }] : [],
        var.init_container == null ? [] : [{ containerName = var.init_container.name, condition = "SUCCESS" }],
      )
      logConfiguration = {
        logDriver = "awslogs"
        options   = merge(local.log_options, { "awslogs-stream-prefix" = var.name })
      }
    },
    var.health_check_command == null ? {} : {
      healthCheck = {
        command     = var.health_check_command
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 180
      }
    },
  )

  # Receives OTLP from the application on localhost:4318 and exports traces to X-Ray.
  # Non-essential: losing traces must never stop the service.
  collector_containers = var.enable_tracing ? [{
    name        = "otel-collector"
    image       = var.otel_collector_image
    essential   = false
    memory      = local.sidecar_memory
    environment = [{ name = "AOT_CONFIG_CONTENT", value = var.otel_collector_config }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { "awslogs-stream-prefix" = "otel-collector" })
    }
  }] : []

  init_containers = var.init_container == null ? [] : [{
    name        = var.init_container.name
    image       = var.init_container.image
    essential   = false
    memory      = local.sidecar_memory
    command     = var.init_container.command
    environment = [for key, value in var.init_container.environment : { name = key, value = value }]
    secrets     = [for key, value in var.init_container.secrets : { name = key, valueFrom = value }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { "awslogs-stream-prefix" = var.init_container.name })
    }
  }]
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name_prefix}/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# --- IAM: execution role (ECS agent: pull image, write logs, read secrets) ----------------

data "aws_iam_policy_document" "execution_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.family}-execution"
  assume_role_policy = data.aws_iam_policy_document.execution_assume.json
}

data "aws_iam_policy_document" "execution" {
  # ecr:GetAuthorizationToken has no resource-level permissions.
  statement {
    sid       = "EcrAuthorizationToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullOwnImage"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [var.ecr_repository_arn]
  }

  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [var.secret_arns] : []

    content {
      sid       = "ReadOwnSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = statement.value
    }
  }

  statement {
    sid       = "DecryptSecretsViaSecretsManager"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "pull-log-read-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# --- IAM: task role (what the application itself may call) -------------------------------

data "aws_iam_policy_document" "task_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${var.region}:${var.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "task" {
  name               = "${local.family}-task"
  assume_role_policy = data.aws_iam_policy_document.task_assume.json
}

# The applications call no AWS API; only the collector sidecar exports traces.
data "aws_iam_policy_document" "task" {
  count = var.enable_tracing ? 1 : 0

  # X-Ray trace ingestion and sampling actions have no resource-level permissions.
  statement {
    sid = "ExportTraces"
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task" {
  count = var.enable_tracing ? 1 : 0

  name   = "export-traces"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task[0].json
}

# --- Task definition and service ----------------------------------------------------------

resource "aws_ecs_task_definition" "this" {
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  # Fargate has no tmpfs; a task-scoped ephemeral volume gives read-only containers a writable /tmp.
  dynamic "volume" {
    for_each = var.readonly_root_filesystem ? ["tmp"] : []

    content {
      name = volume.value
    }
  }

  container_definitions = jsonencode(concat(local.init_containers, local.collector_containers, [local.app_container]))
}

resource "aws_ecs_service" "this" {
  name                    = var.name
  cluster                 = var.cluster_arn
  task_definition         = aws_ecs_task_definition.this.arn
  desired_count           = var.desired_count
  launch_type             = "FARGATE"
  platform_version        = "LATEST"
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  # Start the replacement before stopping the old task; roll back automatically if it never
  # becomes healthy.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = var.load_balancer == null ? null : var.health_check_grace_period_seconds

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.load_balancer == null ? [] : [var.load_balancer]

    content {
      target_group_arn = load_balancer.value.target_group_arn
      container_name   = var.name
      container_port   = load_balancer.value.container_port
    }
  }

  dynamic "service_connect_configuration" {
    for_each = var.service_connect_namespace_arn == null ? [] : [var.service_connect_namespace_arn]

    content {
      enabled   = true
      namespace = service_connect_configuration.value

      # Without a server block the task is a client only: it can resolve other services' aliases.
      dynamic "service" {
        for_each = var.service_connect_alias == null ? [] : [var.service_connect_alias]

        content {
          port_name      = "http"
          discovery_name = service.value
          client_alias {
            dns_name = service.value
            port     = var.container_port
          }
        }
      }

      log_configuration {
        log_driver = "awslogs"
        options    = merge(local.log_options, { "awslogs-stream-prefix" = "service-connect" })
      }
    }
  }

  # Terraform owns the task definition's shape; the cd-aws workflow owns which revision runs
  # (it renders the newest revision of the family with the new image and deploys it).
  lifecycle {
    ignore_changes = [task_definition]
  }
}
