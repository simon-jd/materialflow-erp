# Test and quality report

## Results

The final Maven reactor contains 20 modules. The final report set contains 18
Surefire suites and 31 tests: 31 passed, 0 failed, 0 errors, 0 skipped.

```text
tests=31
failures=0
errors=0
skipped=0
suites=18
```

The full verification command was:

```bash
mvn -q -o -Dmaven.repo.local=/tmp/materialflow-m2 verify
```

Raw log: `/tmp/materialflow-mvn-verify-20260902.log`

SHA-256: `da5213c577368d3d1520f86d7ca433983128d636218f5dfd536ab87cab1248d9`

The final full static-analysis gate also exited 0:

```bash
mvn -q -o -Dmaven.repo.local=/tmp/materialflow-m2 -Pquality -DskipTests verify
```

This gate runs the configured Checkstyle and SpotBugs rules. The build also
executes ArchUnit boundaries and Flyway migrations through the integration suite.
The quiet quality log is empty (successful run with no findings), SHA-256
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

## Coverage

Eleven JaCoCo reports contained 1,386 covered and 424 missed lines: 76.57% across
the modules that emitted reports. This is `measured`, but it is not a claim of
complete reactor-wide coverage; modules without execution data, including the
gateway, are not represented in that aggregate.

## Important exercised behavior

- order-to-cash and procurement-to-receipt end-to-end flows;
- idempotent replay and conflicting key reuse rejection;
- concurrent inventory and credit reservation;
- two-of-three approval decision racing;
- reservation expiry versus shipment racing;
- two relay instances claiming disjoint Outbox rows and stale-claim recovery;
- gRPC synthetic adapters and resilience configuration;
- actor/security context behavior and architecture boundaries.

## Contract and deployment validation

| Check | Result |
|---|---|
| Redocly 2.1.0 OpenAPI lint | pass, one expected localhost-server warning |
| Buf lint | pass |
| Event JSON Schema parse | pass |
| `docker compose config --quiet` | pass |
| Helm lint | pass, zero failed charts |
| Helm template | pass |
| kind Kubernetes API admission and Pod startup | pass, Kubernetes v1.36.1 |
| Helm release readiness | pass, revision 2 `deployed` |
| Core Pod deletion / rolling-update traffic drill | pass, 10/10 rollout writes |
| Failure script `bash -n` | pass |

Rendered Helm manifest SHA-256:
`0dda9b49c6b2cb95fa6bf5cc5e20740e63bfdddf50f8c4fe6dd3c540cd630609`.

The final kind topology had two Ready replicas each for ERP Core and Edge
Gateway and one Ready replica for Event Relay, Stream Projection, and Integration
Adapter. PostgreSQL, Redis, Kafka, and Jaeger dependency Deployments were also
Ready.
