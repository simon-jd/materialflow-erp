# ADR 0009: deploy the AWS environment on ECS Fargate with managed data services

Date: 2026-11-23. Status: accepted for the code; **not yet applied** to an AWS account. What has
been validated without an account is listed in the
[AWS IaC evidence](../evidence/local-2026-11-23-aws-iac/README.md); the
[runbook](../runbooks/aws-deploy.md) covers bootstrap, deploy, verify and teardown.

## Context

The Terraform in `deploy/terraform/aws` stopped at a VPC behind an `enable_aws` flag. Compose,
Helm and kind already cover local and Kubernetes deployment. The goal is a complete, reviewable
AWS environment as code plus a GitHub Actions delivery pipeline, sized for a short-lived demo:
cheap when idle, torn down with one command, and secure enough to be reachable from the internet.

The five applications already read their wiring from environment variables (`application.yml`):
JDBC URL and credentials, Kafka bootstrap servers, Redis host, OIDC issuer and JWKS URIs and the
OTLP endpoint. TLS to the managed services is switched on through Spring's relaxed binding
(`SPRING_KAFKA_SECURITY_PROTOCOL=SSL`, `SPRING_DATA_REDIS_SSL_ENABLED=true`), so no
`application-aws.yml` profile was needed and the local and Compose defaults are untouched.

## Decision

| Concern | Choice | Main alternative |
|---|---|---|
| Compute | ECS on Fargate, ARM64 (Graviton), one service per application | EKS |
| Ingress | CloudFront (console from S3 via OAC; `/api/*`, `/idp/*`, `/actuator/health*` to a public ALB that only CloudFront can use) | ALB with its own domain and certificate |
| Service-to-service | ECS Service Connect aliases (`erp-core`, `stream-projection`, `keycloak`) | Cloud Map DNS only |
| Database | RDS PostgreSQL 17, `db.t4g.micro`, encrypted, RDS-managed master secret | Aurora Serverless v2 |
| Events | Amazon MSK provisioned, 2 × `kafka.t3.small`, Kafka 4.1.x, TLS | self-managed Kafka, MSK Serverless |
| Cache / rate limits | ElastiCache Valkey 8.1, `cache.t4g.micro`, TLS + AUTH token | ElastiCache Redis OSS |
| Identity | Keycloak 26.3.3 as an ECS service, realm baked into the image | Amazon Cognito |
| Delivery | GitHub OIDC → one deploy role scoped to `main` in the `aws-dev` environment | long-lived access keys |

### ECS rather than EKS

- **Cost.** The EKS control plane alone is $0.10/hour (about $73/month), roughly a quarter of the
  whole estimated environment. ECS has no control-plane charge.
- **Coverage already exists.** The Helm chart and kind drills demonstrate Kubernetes (probes,
  PDBs, HPA, network policies, rolling updates). Repeating that on EKS adds node groups or Fargate
  profiles, the AWS Load Balancer Controller and a secrets operator without teaching anything new.
- **Native integration.** Task roles, Secrets Manager injection, ALB target groups, Service Connect
  and the deployment circuit breaker are task-definition fields, not extra controllers.
- **Trade-off.** Two deployment descriptions now exist (Helm values and Terraform task
  definitions). Both consume the same environment-variable contract, so drift is limited to sizing
  and probes, which the [environment tests](../../deploy/terraform/aws/tests) pin for ECS.

Graviton was chosen because nothing blocks it: every runtime image is multi-arch or built by us
for `linux/arm64`, and Fargate ARM is about 20% cheaper per vCPU-hour than x86.

### MSK rather than self-managed Kafka

- Kafka needs stable broker identities and persistent disks; on ECS that means EBS volume
  management, controller quorum and rolling upgrades owned by this repository. MSK runs KRaft
  controllers at no charge, patches brokers one at a time, and provides TLS and KMS encryption.
- **MSK Serverless** was rejected: it only supports IAM authentication (the clients would need the
  `aws-msk-iam-auth` library and new configuration) and its per-cluster hourly price is higher than
  two small provisioned brokers at idle.
- **Version.** MSK offers 4.1.x (since 2025-10-15) and 4.2.x (since 2026-07-15). The default is
  **4.1.x** because the applications use Kafka 4.1.2 clients and every local test and qualification
  ran against a 4.1.2 broker. 4.2 brokers accept 4.1 clients (Kafka keeps client compatibility
  across versions), so moving to 4.2.x is a variable change once it has been qualified locally.
- **Durability trade-off.** Two brokers in two AZs with replication factor 2 and
  `min.insync.replicas=1`, so `acks=all` producers keep working while MSK patches one broker. A
  production cluster would use three AZs, RF 3 and ISR 2. Clients authenticate at the network layer
  only (security groups, TLS); IAM or mTLS client authentication is a follow-up.
- Topics: one partition each, as in Compose, because the consumer DLQ recoverer republishes to the
  same partition number of `<topic>.DLQ`. An init container in the `stream-projection` task creates
  them idempotently before Kafka Streams starts (the race recorded in ADR 0007's qualification).

### Keycloak rather than Cognito

The resource servers read Keycloak-shaped tokens: roles from `realm_access.roles` and the custom
claims `organization_id`, `user_id` and the multi-valued `warehouse_scope`. Cognito would need a
pre-token-generation Lambda (V2 trigger) to reshape every access token, a second user directory
instead of the versioned realm file, and would make AWS behave differently from Compose and kind,
where every JWT/RBAC test runs. Cognito is cheaper (no task to run), but parity wins here.

- The image (`deploy/docker/Dockerfile.keycloak`) is an optimized production build with the realm
  baked in. All public paths live under `/idp`, so one CloudFront behavior covers Keycloak without
  colliding with console routes such as `/auth/callback`. The admin console client gets its
  CloudFront redirect URIs through a placeholder resolved at import time; the shared realm file is
  not changed.
- Storage: its own `keycloak` database on the shared RDS instance (RDS creates only one database,
  so a one-shot `psql` init container creates it idempotently). This survives task restarts; a
  dev-file H2 store would not.
- One task with a local cache. The ALB returns 404 for `/idp/admin/*` and the master realm, so the
  bootstrap administrator is unreachable from the internet.
- Tokens are issued through CloudFront, so the issuer is
  `https://<distribution>.cloudfront.net/idp/realms/materialflow`; signing keys are fetched inside
  the VPC (`http://keycloak:8080/idp/...`), exactly as Compose separates issuer and JWKS URLs.

## Security posture

- CloudFront terminates viewer TLS. The ALB admits only CloudFront's origin-facing prefix list and
  forwards only requests carrying a secret header CloudFront adds; others get 403. Because the ALB
  rewrites `X-Forwarded-Proto` to `http`, CloudFront also sends `Forwarded: proto=https`, which
  Keycloak trusts (`KC_PROXY_HEADERS=forwarded`) so its cookies are `Secure`.
- Inside the VPC: RDS with `rds.force_ssl=1` and `sslmode=require`, MSK TLS only, Valkey with
  in-transit encryption and an AUTH token. One customer-managed KMS key (rotation on) encrypts RDS,
  Performance Insights, MSK, ElastiCache, Secrets Manager and every log group.
- Security-group rules are generated from one flow table (`security_groups.tf`): each allowed
  connection becomes an egress rule on the client and an ingress rule on the server; tasks may
  otherwise only reach HTTPS (AWS APIs through NAT).
- Each service has its own execution role (pull its repository, write its log group, read only its
  secrets) and task role (X-Ray only, for the collector sidecar).
- The deploy role trusts the subject
  `repo:simon-jd/materialflow-erp:environment:aws-dev:ref:refs/heads/main` (the repository's OIDC
  subject template must include `repo`, `context` and `ref`) and may only push these repositories,
  update these services, pass these roles, sync the console bucket and invalidate its distribution.

Accepted scanner findings, each with an inline reason in the code: plain HTTP between CloudFront
and the ALB (needs a custom domain and ACM certificate), an internet-facing ALB (CloudFront custom
origins must be public), no CloudFront access logs and no WAF (cost; the gateway rate-limits),
AES-256 rather than KMS for ECR and the console bucket (public, rebuildable artifacts), no S3
access logs for the console bucket, and Container Insights off by default (custom-metric cost).

## Consequences

- Terraform owns each task definition's shape; the `cd-aws` workflow owns which revision runs. It
  renders the newest revision of each family with the new image, so a Terraform change to a task
  definition goes live with the next deployment, and `terraform apply` never rolls back an image.
- ECS services are only created once `bootstrap_image_tag` points at pushed images, which gives a
  two-apply bootstrap (documented in the runbook) instead of tasks crash-looping on missing images.
- The applications and Keycloak use the RDS master user. Per-application database users would
  need a SQL bootstrap step. RDS rotates the managed secret every 7 days; running tasks keep the
  old password until they restart, so a deployment is required after a rotation (runbook).
- The default `*.cloudfront.net` certificate fixes the minimum viewer TLS version; raising it needs
  a custom domain. The console currently receives the managed security-headers policy, not a CSP.
- The realm's synthetic users and passwords are public. The environment is meant to run for a
  demonstration and then be destroyed; it holds only synthetic data.
- Estimated cost is about $0.37/hour ($9/day) in us-east-1, dominated by Fargate (42%) and MSK
  (25%); see the runbook's dated table. Nothing has been billed yet.
