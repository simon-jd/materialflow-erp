locals {
  # Every allowed connection inside the VPC, in one place. Each flow becomes an egress rule on
  # the client and an ingress rule on the server; anything not listed is denied.
  network_flows = {
    "alb->edge-gateway"               = { from = "alb", to = "edge-gateway", port = 8081 }
    "alb->keycloak"                   = { from = "alb", to = "keycloak", port = 8080 }
    "alb->keycloak-health"            = { from = "alb", to = "keycloak", port = 9000 }
    "edge-gateway->erp-core"          = { from = "edge-gateway", to = "erp-core", port = 8080 }
    "edge-gateway->stream-projection" = { from = "edge-gateway", to = "stream-projection", port = 8082 }
    "edge-gateway->keycloak"          = { from = "edge-gateway", to = "keycloak", port = 8080 }
    "edge-gateway->valkey"            = { from = "edge-gateway", to = "valkey", port = 6379 }
    "erp-core->keycloak"              = { from = "erp-core", to = "keycloak", port = 8080 }
    "erp-core->valkey"                = { from = "erp-core", to = "valkey", port = 6379 }
    "erp-core->postgres"              = { from = "erp-core", to = "postgres", port = 5432 }
    "event-relay->postgres"           = { from = "event-relay", to = "postgres", port = 5432 }
    "keycloak->postgres"              = { from = "keycloak", to = "postgres", port = 5432 }
    "erp-core->kafka"                 = { from = "erp-core", to = "kafka", port = 9094 }
    "event-relay->kafka"              = { from = "event-relay", to = "kafka", port = 9094 }
    "stream-projection->kafka"        = { from = "stream-projection", to = "kafka", port = 9094 }
  }

  security_group_ids = merge(
    { for name, group in aws_security_group.service : name => group.id },
    {
      alb      = aws_security_group.alb.id
      postgres = module.postgres.security_group_id
      kafka    = module.kafka.security_group_id
      valkey   = module.cache.security_group_id
    },
  )
}

resource "aws_security_group" "service" {
  for_each = toset(local.ecs_services)

  name        = "${local.name}-${each.key}"
  description = "ECS tasks of ${each.key}"
  vpc_id      = module.network.vpc_id

  tags = { Name = "${local.name}-${each.key}" }
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public ALB, reachable only from CloudFront origin-facing addresses"
  vpc_id      = module.network.vpc_id

  tags = { Name = "${local.name}-alb" }
}

data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from CloudFront origin-facing servers only"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_ingress_rule" "flow" {
  for_each = local.network_flows

  security_group_id            = local.security_group_ids[each.value.to]
  referenced_security_group_id = local.security_group_ids[each.value.from]
  description                  = "${each.key} on ${each.value.port}"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

resource "aws_vpc_security_group_egress_rule" "flow" {
  for_each = local.network_flows

  security_group_id            = local.security_group_ids[each.value.from]
  referenced_security_group_id = local.security_group_ids[each.value.to]
  description                  = "${each.key} on ${each.value.port}"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

# Tasks reach AWS APIs (ECR, Secrets Manager, CloudWatch Logs, X-Ray) and the public ECR
# gallery over HTTPS through the NAT gateway; no other internet egress is allowed.
resource "aws_vpc_security_group_egress_rule" "service_https" {
  for_each = aws_security_group.service

  security_group_id = each.value.id
  description       = "HTTPS to AWS service endpoints"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
