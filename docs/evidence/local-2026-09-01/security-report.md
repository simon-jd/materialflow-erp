# Security and supply-chain report

## Final image scans

Trivy 0.73.0 scanned the exact five final local image IDs listed in
[environment.md](environment.md). Each result contained zero HIGH and zero
CRITICAL vulnerabilities and zero detected secrets.

| Image scan | HIGH | CRITICAL | Secrets | Raw JSON SHA-256 |
|---|---:|---:|---:|---|
| ERP Core | 0 | 0 | 0 | `0dcceb1466e5cc3c9eaefd7a6e41b00a89fd343ead456ac5b9310b744fd49f0c` |
| Event Relay | 0 | 0 | 0 | `f80983acaee729bb324d8a733808c6b02e1104178c6decb41bc8cc7ea5c17e11` |
| Edge Gateway | 0 | 0 | 0 | `57f9c70ec05b7bb4193a9b591e961caa6fdc1f0fd2b79fbb611022998aaf8c7a` |
| Stream Projection | 0 | 0 | 0 | `93d8124a6e44833a6c29c52cb179bd93df9e50dfddf9c00f8cebe6faa2f46a1b` |
| Integration Adapter | 0 | 0 | 0 | `7c2a9b798aafdd6708887833411c78bbb38f515315716bc762782e8b4ae4f2cc` |

The offline workspace config/secret scan reported zero secrets and zero
HIGH/CRITICAL misconfigurations. Raw JSON SHA-256:
`110c09c3a9fdc96ef4f3da113ead4aca63840fbbb30102b7e2045884e86264c3`.

The final application images run as `10001:10001`, enable
`no-new-privileges`, and are used read-only by Compose. The Dockerfile upgrades
Alpine packages and installs only the RocksDB runtime requirement in addition to
the JRE. Dependency pins include the remediated Netty, PostgreSQL JDBC, and gRPC
versions.

The local kind dependency Deployments also declare explicit non-root UIDs/GIDs,
`RuntimeDefault` seccomp, `allowPrivilegeEscalation: false`, drop all Linux
capabilities, and use read-only root filesystems. PostgreSQL, Redis, Kafka, and
Jaeger were all restarted and became Ready under these settings; Kafka uses
explicit writable config/log/data/tmp volumes rather than relaxing the root
filesystem policy.

## JWT, RBAC, and tenant isolation

The final Compose verification enabled JWT enforcement in both Gateway and ERP
Core and used synthetic Keycloak identities. The executable test
`tests/security/jwt-rbac-e2e.sh` produced:

```text
unauthenticated=401 authorized=201 forbidden=403 cross_tenant=404 order_id=e47ee160-aff6-477e-b3a3-18c865d1c29e
```

The authorized request deliberately supplied spoofed organization, actor, role,
and warehouse headers; the Core derived all effective identity and scope from
the signed JWT. A token without `SALES_ORDER_WRITE` was denied, and a valid
writer token for a different organization could not read the created order.
Unit coverage also asserts that a spoofed warehouse header cannot widen JWT
warehouse scope.

## Final CycloneDX SBOMs

| Image | Components | SBOM SHA-256 |
|---|---:|---|
| ERP Core | 229 | `8ad0f4f7fc28ea5ed2f0c674f047a8a98efb536234771f9ed4bd3f5aca30d36f` |
| Event Relay | 159 | `a23d66b62e96fa22c810714f77c172ba5994c323876edbd4578bc67164b1b51a` |
| Edge Gateway | 202 | `5950862493e472aaef6925f5524a4080b098acbea3dd7af565cc21e62c7d5a0b` |
| Stream Projection | 158 | `bb2d584cb44979e7ab94987e0e7a4a67c0ebed90a8dfa6fbb6f4f4a994974f23` |
| Integration Adapter | 184 | `c7eecda0c2f3e9dd1bf800234da8f196c1644d2c38d1f5c3621923e3bc20f22e` |

Raw files are `/tmp/materialflow-<app>-sbom-final-20260902.cdx.json`.

## Limitations

A full online filesystem dependency scan could not complete because Maven
Central returned HTTP 429 during that separate attempt. The exact packaged image
JARs were scanned successfully and are the basis for the zero HIGH/CRITICAL
claim. Gitleaks is configured in CI but was not run locally; local secret evidence
comes from Trivy. License-policy review, image signing, provenance attestation,
and penetration testing remain `unknown`. The formal JMeter protocol is executed
with JWT authorization enabled and is reported separately in
[benchmark-report.md](benchmark-report.md).
