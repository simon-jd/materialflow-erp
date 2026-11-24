# AWS IaC and delivery pipeline: offline validation — November 23, 2026

This records how far the AWS environment (`deploy/terraform/aws`), the `cd-aws` and `terraform`
workflows and the images they deploy were validated **without an AWS account**. No
`terraform plan`/`apply` and no AWS API call was made; no credentials were used. Everything below
is **measured** on the stated host unless labelled otherwise; the cost table in the
[runbook](../../runbooks/aws-deploy.md#cost-estimate) is **estimated**; real behavior in AWS is
**unknown** until applied. Design rationale: [ADR 0009](../../adr/0009-aws-ecs-fargate-deployment.md).
Machine-readable summary: [summary.json](summary.json).

## Environment

- Apple Silicon Mac (Darwin 27, arm64), Docker Desktop engine 29.8.0 with 12 CPUs / 12.5 GB
  shared by four parallel agents. Container images run natively as `linux/arm64`, the
  architecture the ECS tasks use.
- Terraform 1.17.2 (AWS provider 6.74.0, random 3.9.1), TFLint 0.66.0 with
  `tflint-ruleset-aws` 0.50.0 and the bundled Terraform ruleset (preset `all`), Trivy 0.76.2,
  actionlint 1.7.12 with ShellCheck 0.11.0, mermaid-cli 12.0.0, all run from their Docker images.
- Runtime images under test: Keycloak 26.3.3, PostgreSQL 17.11-alpine, Redis 8.2.1-alpine,
  apache/kafka 4.1.2, ADOT collector 0.51.0, nginx 1.29-alpine (local stand-in for CloudFront
  and ALB routing), and the five application images built here.

## Results

| Check | Result | Raw output |
|---|---|---|
| `terraform fmt -check -recursive` | pass | — |
| `terraform init -backend=false` + `validate` | pass | — |
| `terraform test` (mocked `aws` and `random` providers) | 17 runs, 17 passed | `raw/terraform-test.txt` |
| Mutation check: `-var github_branch=develop` | the OIDC subject run fails, as intended | — |
| TFLint, root with local modules and `--recursive` | 0 issues | `raw/tflint.txt` |
| Trivy config scan | 0 unaccepted findings (exit 0) | `raw/trivy-config.txt` |
| Trivy with every `#trivy:ignore` removed | 14 findings in 8 checks, each accepted inline with a reason | `raw/trivy-without-ignores.tsv` |
| actionlint on `cd-aws.yml` and `terraform.yml` | 0 errors | — |
| ShellCheck on the smoke test and init scripts | 0 issues | — |
| Mermaid architecture diagram renders | pass | — |
| CloudFront SPA function on 6 sample paths | extensionless paths → `/index.html`, files unchanged | `raw/spa-routing-function.txt` |
| 5 app images, multi-stage `Dockerfile`, `--platform linux/arm64` | 5 built, all `linux/arm64` | `raw/full-*.log` |
| 5 app images, `Dockerfile.runtime` from host-built JARs (the CD path) | 5 built; SHA-256 of `/opt/materialflow/app.jar` equals the tested JAR in all 5 | `raw/runtime-summary.txt` |
| Keycloak image (`Dockerfile.keycloak`), `linux/arm64` | builds; first start ready on `:9000/health/ready` within 15–23 s across the runs of this session | `raw/keycloak-check-*.txt` |
| Keycloak DB init script, run twice | `CREATE DATABASE`, then `database keycloak already exists` | same |
| Token from `/idp/.../token` | issuer `<public URL>/idp/realms/materialflow`; realm roles, `organization_id`, `user_id`, `warehouse_scope` present | same |
| JWKS at the in-network URL used by the resource servers | 2 keys (RS256, RSA-OAEP) | same |
| Discovery requested with the ALB's Host header | issuer, token and JWKS endpoints all use the public URL | same |
| Restart against the same database | `Realm 'materialflow' already exists. Import skipped` | same |
| Admin console client (realm from the admin-console branch) | public-origin callback accepted (200), foreign callback rejected (400) | `raw/keycloak-check-admin-console-realm.txt` |
| Cookies behind an HTTP hop with an HTTPS public URL | with `Forwarded: proto=https`: `Secure; SameSite=None`; without: no `Secure`, `SameSite=Lax`, "Non-secure context" logged | `raw/keycloak-cookie-check.txt` |
| Topic init script against Kafka 4.1.2, run twice | 3 topics created, second run a no-op, exit 0 both times | `raw/sidecar-check.txt` |
| Collector config (`AOT_CONFIG_CONTENT`) in ADOT 0.51.0 | starts, OTLP HTTP on `127.0.0.1:4318`, "Everything is ready" | `raw/otel-collector.log` |
| Local edge end-to-end (below) | smoke test passed | `raw/edge-e2e.txt` |

### Local edge end-to-end

`scripts/edge-e2e.sh` runs PostgreSQL, Redis, the AWS Keycloak image, the `linux/arm64`
erp-core and edge-gateway runtime images configured with the same environment variables as the
ECS task definitions (TLS switches excepted, as the local data stores run without TLS), and an
nginx container that routes `/idp/*` to Keycloak and `/api/*` and `/actuator/health` to the
gateway, like the CloudFront behaviors and ALB rules. Observed in one run:

- The ECS container health-check command (BusyBox `wget` on `/actuator/health/liveness`) passes
  inside both images, and the ALB target-group path `/actuator/health/readiness` returns UP.
- `tests/deployment/aws-smoke.sh` passes against the edge URL: gateway health UP; token issuer
  equals the public URL; unauthenticated order read 401; authenticated read of a random order 404
  `RESOURCE_NOT_FOUND` from erp-core (issuer from the public URL, signing keys from the in-network
  `http://keycloak:8080/idp/...` URL, as configured for AWS).
- No ERROR-level log line in erp-core or edge-gateway.

## Defects found and fixed during validation

- **Path collision.** Keycloak was first published under `/auth`, which would have captured the
  admin console's `/auth/callback` route. Keycloak now lives under `/idp`.
- **Unreachable discovery endpoints.** With `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, discovery
  requested through CloudFront would derive token and JWKS URLs from the ALB's Host header. The
  setting was removed; the resource servers use the JWKS URL directly.
- **Cookies without `Secure`.** The ALB rewrites `X-Forwarded-Proto` to `http`, so Keycloak treated
  every request as insecure. CloudFront now sends `Forwarded: proto=https` and Keycloak trusts
  `forwarded` headers (verified above).
- Unnecessary `#trivy:ignore` directives (checks that never fired) were removed, so each remaining
  ignore suppresses a real, documented finding.

## Not validated (needs an AWS account)

`terraform plan`/`apply` and provider-side validation; regional availability of the chosen engine
versions and instance classes (MSK 4.1.x on `kafka.t3.small`, Valkey 8.1, RDS PostgreSQL 17 on
`db.t4g.micro` with Performance Insights); GitHub OIDC role assumption with the customized subject;
ECR pushes; ECS deployments, the circuit breaker and Service Connect; JVM TLS to RDS, MSK and
Valkey; RDS secret rotation; X-Ray delivery; CloudFront routing; the smoke test against AWS; real
cost. The `cd-aws` workflow has not run (it is skipped until `AWS_DEPLOY_ROLE_ARN` exists).

## Reproduction

From the repository root (no AWS credentials needed):

```bash
cd deploy/terraform/aws
tf() { docker run --rm -v "$PWD":/work -w /work hashicorp/terraform:1.17.2 "$@"; }
tf fmt -check -recursive && tf init -backend=false -input=false && tf validate && tf test
docker run --rm -v "$PWD":/data -w /data --entrypoint /bin/sh ghcr.io/terraform-linters/tflint:v0.66.0 \
  -c 'tflint --init && tflint --format compact && tflint --recursive --config /data/.tflint.hcl'
docker run --rm -v "$PWD":/src:ro aquasec/trivy:0.76.2 config --skip-dirs .terraform --exit-code 1 /src
cd ../../..
docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12 .github/workflows/cd-aws.yml .github/workflows/terraform.yml
docker run --rm -v "$PWD":/mnt koalaman/shellcheck:v0.11.0 tests/deployment/aws-smoke.sh deploy/terraform/aws/scripts/*.sh

# Images and runtime checks (Docker; scripts write to raw/, which is git-ignored)
ev=docs/evidence/local-2026-11-23-aws-iac/scripts
bash $ev/build-arm64-multistage.sh                   # multi-stage Dockerfile, 5 apps
JAVA_HOME=/path/to/jdk-21 bash $ev/build-arm64-runtime.sh   # mvn package + Dockerfile.runtime
bash $ev/keycloak-check.sh                           # repository realm
bash $ev/keycloak-check.sh path/to/realm-with-admin-console-client.json admin-console-realm
bash $ev/keycloak-cookie-check.sh                    # needs the admin-console-realm image above
bash $ev/sidecar-check.sh                            # topic init + collector config
bash $ev/edge-e2e.sh                                 # needs the repo-realm Keycloak and arm64 runtime images
```

The scripts use container names prefixed `mf-awscd-` and loopback ports 28480–28490 / 29480–29481,
and remove their containers and networks on exit.
