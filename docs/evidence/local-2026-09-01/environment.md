# Environment

## Provenance

- Evidence scope: `local_only`
- Workspace: `/home/postedism/Desktop/Java ERP`
- Git metadata: a new, honest local baseline is stored in `.git-local` because
  the desktop environment mounts an empty `.git` directory read-only; use
  `git --git-dir=.git-local` for status/log commands. The verified source
  baseline is `431774cb7d75d7d09e37eaea8576dbcd4912bdea`; it records this local
  implementation and is not represented as historical employment evidence.
- OS: Linux `7.0.0-29-generic`, amd64
- CPU: Intel Core i9-13900K, 24 cores / 32 logical CPUs
- Memory at measurement: 123 GiB total, 107 GiB available

## Toolchain

| Component | Version |
|---|---|
| Temurin JDK | 21.0.12+8 LTS |
| Maven | 3.9.16 |
| Docker Engine | 29.7.2 |
| Docker Compose | 5.3.1 |
| Helm | 4.2.3 |
| kubectl client | 1.36.3 |
| kind | 0.32.0 |
| Kubernetes server | 1.36.1 |
| Trivy | 0.73.0 |
| Buf | 1.72.0 |
| JMeter | 5.6.3 |

Trivy vulnerability DB version 2 was downloaded at
`2026-09-01T06:48:44Z`; Java DB version 1 was downloaded at
`2026-09-01T06:49:58Z`.

## Local topology

Docker Compose runs PostgreSQL 17.6, Redis 8.2.1, Kafka 4.1.0, Keycloak
26.3.3, Jaeger 2.10.0, OpenTelemetry Collector 0.135.0, Prometheus 3.6.0,
Grafana 12.2.0, and five Java applications. The application containers use a
read-only root filesystem, `no-new-privileges`, and user `10001:10001`.

| Endpoint | URL |
|---|---|
| Edge Gateway | `http://localhost:8081` |
| ERP Core | `http://localhost:8080` |
| Stream Projection | `http://localhost:8082` |
| Integration Adapter | `http://localhost:8083` |
| Event Relay | `http://localhost:8084` |
| Grafana | `http://localhost:3000` |
| Prometheus | `http://localhost:9095` |
| Jaeger | `http://localhost:16686` |
| Keycloak | `http://localhost:8180` |
| Redis host port | `6389` |

Compose defaults `MATERIALFLOW_SECURITY_ENABLED=false` for frictionless local
development. The final security and formal performance runs explicitly set it
to `true` for both Gateway and ERP Core and used Keycloak-signed JWTs. The local
synthetic realm uses a two-hour access-token lifespan solely so the 90-minute
formal test can run without mid-sample authentication expiry.

## Local Kubernetes topology

The `kind-materialflow` cluster runs Kubernetes v1.36.1. Helm release
`materialflow` revision 2 is `deployed`; ERP Core and Edge Gateway have two
replicas each, the other three applications have one each, and all were Ready
and Available after the Pod-deletion and rolling-update drill. Local dependency
Deployments use PostgreSQL 17.6, Redis 8.2.1, Kafka 4.1.0, and Jaeger 2.10.0.

## Final application image identities

| Image | Image ID |
|---|---|
| `materialflow-erp-core:latest` | `sha256:eb23b490fcbe707cfe12758afc786189890639266d9b7864d1dfd21ad29f7c6d` |
| `materialflow-event-relay:latest` | `sha256:b5010f9227ba2ede9334785ab14b3596b676dda597e48987deb0098de54f50ec` |
| `materialflow-edge-gateway:latest` | `sha256:03407030af0e197869b6721f62ea4336fb2583db0e5bdb6a2602354201cf96da` |
| `materialflow-stream-projection:latest` | `sha256:b094dc4fe3df1f198d16ddef0a12d8ca8d9a92992fa94b7caaba90fbc54bff9b` |
| `materialflow-integration-adapter:latest` | `sha256:467c595f22bf328a8bc16dcc6f4b1bc9a18769026bd940d4b548fde49a79d9aa` |
