# Container Insights is a variable because it bills CloudWatch custom metrics per task and
# service; the default keeps the demo inside its cost estimate.
#trivy:ignore:AVD-AWS-0034
resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = var.container_insights
  }
}

resource "aws_service_discovery_http_namespace" "this" {
  name        = local.name
  description = "ECS Service Connect namespace for ${local.name}"
}

resource "random_password" "keycloak_admin" {
  length  = 32
  special = false
}

# Temporary master-realm admin Keycloak creates on first start. The master realm and admin
# console are blocked at the ALB, so this account is only usable from inside the VPC.
resource "aws_secretsmanager_secret" "keycloak_admin" {
  name_prefix             = "${local.name}/keycloak-bootstrap-admin-"
  description             = "Keycloak bootstrap admin password for ${local.name}"
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "keycloak_admin" {
  secret_id     = aws_secretsmanager_secret.keycloak_admin.id
  secret_string = random_password.keycloak_admin.result
}

locals {
  public_base_url = "https://${module.edge.cloudfront_domain_name}"

  # Tokens are issued through CloudFront, so that is the issuer every resource server checks.
  # Signing keys are fetched inside the VPC through Service Connect, exactly as in Compose.
  oidc_issuer_uri  = "${local.public_base_url}/idp/realms/materialflow"
  oidc_jwk_set_uri = "http://keycloak:8080/idp/realms/materialflow/protocol/openid-connect/certs"

  db_secret  = module.postgres.master_user_secret_arn
  jdbc_base  = "jdbc:postgresql://${module.postgres.address}:${module.postgres.port}"
  kafka_tls  = module.kafka.bootstrap_brokers_tls
  redis_host = module.cache.primary_endpoint_address
  redis_port = tostring(module.cache.port)

  # Third-party images: public ECR gallery (no Docker Hub rate limits) or our own mirror.
  postgres_client_image = "public.ecr.aws/docker/library/postgres:17.11-alpine"
  kafka_tools_image     = "${module.ecr.repository_urls["kafka-tools"]}:${var.kafka_tools_image_tag}"
  otel_collector_image  = "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0"

  otel_collector_config = yamlencode({
    receivers  = { otlp = { protocols = { http = { endpoint = "127.0.0.1:4318" } } } }
    processors = { batch = {} }
    exporters  = { awsxray = { region = var.aws_region } }
    service = {
      pipelines = { traces = { receivers = ["otlp"], processors = ["batch"], exporters = ["awsxray"] } }
    }
  })

  # Settings every Spring Boot service shares. Everything is wired through the environment
  # variables each application.yml already reads, plus Spring's relaxed binding for TLS; no
  # AWS-specific profile file is needed.
  spring_environment = {
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED = "true"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY   = tostring(var.trace_sampling_probability)
    OTEL_EXPORTER_OTLP_ENDPOINT               = "http://localhost:4318/v1/traces"
  }
  kafka_tls_environment = { SPRING_KAFKA_SECURITY_PROTOCOL = "SSL" }
  oidc_environment = {
    MATERIALFLOW_SECURITY_ENABLED = "true"
    OIDC_ISSUER_URI               = local.oidc_issuer_uri
    OIDC_JWK_SET_URI              = local.oidc_jwk_set_uri
  }

  services = {
    "erp-core" = {
      cpu             = 1024
      memory          = 2048
      port            = 8080
      service_connect = true
      alias           = "erp-core"
      environment = merge(local.spring_environment, local.kafka_tls_environment, local.oidc_environment, {
        MATERIALFLOW_DB_URL = "${local.jdbc_base}/materialflow?sslmode=require"
        # db.t4g.micro allows roughly 80 connections; leave room for a rolling deploy.
        MATERIALFLOW_DB_POOL_SIZE            = "10"
        MATERIALFLOW_KAFKA_BOOTSTRAP_SERVERS = local.kafka_tls
        MATERIALFLOW_KAFKA_CONSUMER_ENABLED  = "true"
        MATERIALFLOW_REDIS_HOST              = local.redis_host
        MATERIALFLOW_REDIS_PORT              = local.redis_port
        SPRING_DATA_REDIS_SSL_ENABLED        = "true"
      })
      secrets = {
        MATERIALFLOW_DB_USER       = "${local.db_secret}:username::"
        MATERIALFLOW_DB_PASSWORD   = "${local.db_secret}:password::"
        SPRING_DATA_REDIS_PASSWORD = module.cache.auth_secret_arn
      }
      secret_arns    = [local.db_secret, module.cache.auth_secret_arn]
      init_container = null
      load_balancer  = null
    }

    "event-relay" = {
      cpu             = 512
      memory          = 1024
      port            = 8084
      service_connect = false
      alias           = null
      environment = merge(local.spring_environment, local.kafka_tls_environment, {
        POSTGRES_URL            = "${local.jdbc_base}/materialflow?sslmode=require"
        KAFKA_BOOTSTRAP_SERVERS = local.kafka_tls
      })
      secrets = {
        POSTGRES_USER     = "${local.db_secret}:username::"
        POSTGRES_PASSWORD = "${local.db_secret}:password::"
      }
      secret_arns    = [local.db_secret]
      init_container = null
      load_balancer  = null
    }

    "stream-projection" = {
      cpu             = 512
      memory          = 1024
      port            = 8082
      service_connect = true
      alias           = "stream-projection"
      environment = merge(local.spring_environment, local.kafka_tls_environment, {
        KAFKA_BOOTSTRAP_SERVERS = local.kafka_tls
        KAFKA_STREAMS_STATE_DIR = "/tmp/materialflow-kafka-streams"
      })
      secrets     = {}
      secret_arns = []
      # The init container's image is our kafka-tools mirror.
      extra_repositories = ["kafka-tools"]
      # Same idempotent topic creation as Compose's kafka-init: Streams must not start against a
      # missing source topic (see docs/runbooks/runtime-qualification.md).
      init_container = {
        name    = "create-topics"
        image   = local.kafka_tools_image
        command = ["/bin/sh", "-ec", file("${path.module}/scripts/create-topics.sh")]
        environment = {
          KAFKA_BOOTSTRAP_SERVERS = local.kafka_tls
          REPLICATION_FACTOR      = "2"
        }
        secrets = {}
      }
      load_balancer = null
    }

    "integration-adapter" = {
      cpu             = 512
      memory          = 1024
      port            = 8083
      service_connect = false
      alias           = null
      environment = merge(local.spring_environment, {
        # The synthetic supplier simulator calls its own gRPC port inside the task.
        GRPC_CLIENT_HOST = "localhost"
      })
      secrets        = {}
      secret_arns    = []
      init_container = null
      load_balancer  = null
    }

    "edge-gateway" = {
      cpu             = 512
      memory          = 1024
      port            = 8081
      service_connect = true
      alias           = null
      environment = merge(local.spring_environment, local.oidc_environment, {
        ERP_CORE_URI                  = "http://erp-core:8080"
        PROJECTION_URI                = "http://stream-projection:8082"
        REDIS_HOST                    = local.redis_host
        MATERIALFLOW_REDIS_PORT       = local.redis_port
        SPRING_DATA_REDIS_SSL_ENABLED = "true"
      })
      secrets        = { SPRING_DATA_REDIS_PASSWORD = module.cache.auth_secret_arn }
      secret_arns    = [module.cache.auth_secret_arn]
      init_container = null
      load_balancer  = { target_group_arn = module.edge.gateway_target_group_arn, container_port = 8081 }
    }
  }
}

module "service" {
  source   = "./modules/ecs-service"
  for_each = { for name, service in local.services : name => service if var.bootstrap_image_tag != null }

  name          = each.key
  name_prefix   = local.name
  region        = var.aws_region
  account_id    = var.aws_account_id
  cluster_arn   = aws_ecs_cluster.this.arn
  image         = "${module.ecr.repository_urls[each.key]}:${var.bootstrap_image_tag}"
  cpu           = try(var.service_overrides[each.key].cpu, null) == null ? each.value.cpu : var.service_overrides[each.key].cpu
  memory        = try(var.service_overrides[each.key].memory, null) == null ? each.value.memory : var.service_overrides[each.key].memory
  desired_count = try(var.service_overrides[each.key].desired_count, null) == null ? 1 : var.service_overrides[each.key].desired_count

  ecr_repository_arns = [for repository in concat([each.key], try(each.value.extra_repositories, [])) : module.ecr.repository_arns[repository]]
  container_port      = each.value.port
  environment         = each.value.environment
  secrets             = each.value.secrets
  secret_arns         = each.value.secret_arns
  init_container      = each.value.init_container
  load_balancer       = each.value.load_balancer
  kms_key_arn         = aws_kms_key.main.arn

  # The alpine runtime image ships BusyBox wget; liveness excludes dependencies by design so a
  # Kafka or Redis outage does not make ECS restart every task.
  health_check_command = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${each.value.port}/actuator/health/liveness || exit 1"]

  subnet_ids                    = module.network.private_subnet_ids
  security_group_ids            = [aws_security_group.service[each.key].id]
  service_connect_namespace_arn = each.value.service_connect ? aws_service_discovery_http_namespace.this.arn : null
  service_connect_alias         = each.value.alias

  otel_collector_image  = local.otel_collector_image
  otel_collector_config = local.otel_collector_config
  log_retention_days    = var.log_retention_days
}

module "keycloak" {
  source = "./modules/ecs-service"
  count  = var.bootstrap_image_tag == null ? 0 : 1

  name          = "keycloak"
  name_prefix   = local.name
  region        = var.aws_region
  account_id    = var.aws_account_id
  cluster_arn   = aws_ecs_cluster.this.arn
  image         = "${module.ecr.repository_urls["keycloak"]}:${var.bootstrap_image_tag}"
  cpu           = try(var.service_overrides["keycloak"].cpu, null) == null ? 1024 : var.service_overrides["keycloak"].cpu
  memory        = try(var.service_overrides["keycloak"].memory, null) == null ? 2048 : var.service_overrides["keycloak"].memory
  desired_count = 1 # cache=local in the image: a single instance by design

  ecr_repository_arns = [module.ecr.repository_arns["keycloak"]]
  container_port      = 8080
  extra_port_mappings = [{ name = "management", port = 9000, app_protocol = "http" }]
  environment = {
    KC_DB_URL           = "${local.jdbc_base}/keycloak?sslmode=require"
    KC_DB_POOL_MAX_SIZE = "10"
    # Every URL Keycloak generates (issuer, discovery endpoints, redirects) uses the CloudFront
    # host. Backchannel URLs are deliberately not dynamic: behind CloudFront the request Host is
    # the ALB's DNS name, which browsers cannot reach. Resource servers call the JWKS URL directly.
    KC_HOSTNAME                 = "${local.public_base_url}/idp"
    KC_HTTP_ENABLED             = "true"
    KC_PROXY_HEADERS            = "forwarded"
    KC_BOOTSTRAP_ADMIN_USERNAME = "bootstrap-admin"
    # Resolves the admin console redirect URI placeholders in the baked-in realm.
    MATERIALFLOW_PUBLIC_URL = local.public_base_url
  }
  secrets = {
    KC_DB_USERNAME              = "${local.db_secret}:username::"
    KC_DB_PASSWORD              = "${local.db_secret}:password::"
    KC_BOOTSTRAP_ADMIN_PASSWORD = aws_secretsmanager_secret.keycloak_admin.arn
  }
  secret_arns = [local.db_secret, aws_secretsmanager_secret.keycloak_admin.arn]
  kms_key_arn = aws_kms_key.main.arn

  # Keycloak gets its own database on the shared RDS instance; RDS can only create one database
  # itself, so a one-shot psql container creates it idempotently before Keycloak starts.
  init_container = {
    name    = "create-database"
    image   = local.postgres_client_image
    command = ["/bin/sh", "-ec", file("${path.module}/scripts/create-keycloak-database.sh")]
    environment = {
      PGHOST     = module.postgres.address
      PGPORT     = tostring(module.postgres.port)
      PGDATABASE = "materialflow"
      PGSSLMODE  = "require"
    }
    secrets = {
      PGUSER     = "${local.db_secret}:username::"
      PGPASSWORD = "${local.db_secret}:password::"
    }
  }

  # Keycloak writes its compiled state under /opt/keycloak/data, and its UBI image has no
  # wget/curl for a container health check: the ALB probes :9000/health/ready instead.
  readonly_root_filesystem          = false
  health_check_command              = null
  load_balancer                     = { target_group_arn = module.edge.keycloak_target_group_arn, container_port = 8080 }
  health_check_grace_period_seconds = 300

  subnet_ids                    = module.network.private_subnet_ids
  security_group_ids            = [aws_security_group.service["keycloak"].id]
  service_connect_namespace_arn = aws_service_discovery_http_namespace.this.arn
  service_connect_alias         = "keycloak"

  enable_tracing     = false
  log_retention_days = var.log_retention_days
}
