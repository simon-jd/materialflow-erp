# Azure AKS platform bring-up, 2026-11-24

First run of MaterialFlow in a real cloud environment: the AKS stack from
`deploy/terraform/azure`, the platform chart and the application chart, reachable at
`https://materialflow-erp.westus.cloudapp.azure.com`. Design: [ADR 0012](../../adr/0012-azure-aks-deployment.md).
Operations: [runbook](../../runbooks/azure-deploy.md). All data is synthetic.

Labels follow the repository convention: **measured** (observed in this run), **derived**
(computed from measured values or static definitions), **estimated** (list prices x hours).

## Summary

| # | Check | Result | Label |
|---|---|---|---|
| 1 | `terraform fmt -check`, `validate`, TFLint (azurerm ruleset 0.33.0), Trivy config | pass; TFLint 0 issues (1 rule disabled with a reason); Trivy 0 unaccepted findings | measured |
| 2 | `terraform apply` of a reviewed plan | 42 resources created in 16 min 30 s; a second plan found 2 drifts, both codified; third plan: no changes | measured |
| 3 | All pods Ready | 55 of 55 pods Ready in all namespaces (15 in `materialflow`, 2 Envoy gateway pods, 3 cert-manager, 2 istiod, 33 kube-system) | measured |
| 4 | Five `/actuator/health` endpoints | erp-core, edge-gateway, stream-projection, integration-adapter, event-relay: `UP` (3 runs) | measured |
| 5 | HTTPS with a valid certificate | Let's Encrypt production certificate via cert-manager HTTP-01; TLS 1.3; curl and `openssl -verify_return_error` verify; HTTP 301 to HTTPS; HSTS | measured |
| 6 | `tests/security/jwt-rbac-e2e.sh` against the public URL | 401 / 201 / 403 `ACCESS_DENIED` / 404 cross-tenant, 3 of 3 runs | measured |
| 7 | Qualification smoke adapted to Kubernetes (`tests/deployment/k8s-qualification-smoke.py`) | passed 3 of 3 runs | measured |
| 8 | Console Playwright e2e (login, create, list/detail, read-only + API 403, maker-checker approval, CSP) against the public URL | 4/4 on a fresh database (runs 1 and 3); 2/4 in run 2, which reused run 1's consumed stock (see below) | measured |
| 9 | One order traced API -> PostgreSQL -> outbox -> Kafka -> consumer | outbox row `PUBLISHED`, Kafka record at partition 0, consumer_inbox row, one Jaeger trace with 18 spans across gateway, erp-core and event-relay | measured |
| 10 | Flyway on the managed server | 7 of 7 migrations `success = t` on PostgreSQL 17.11 (installed by `mfadmin`), before and after a database reset | measured |
| 11 | Fault-injection chart switch | without it, `kill -KILL 1` from `kubectl exec` is ignored (JVM is PID 1); with it, SIGKILL of the JVM restarts the container in place (same pod and IP, exit 137, restart count 1) for erp-core and event-relay | measured, n = 1 each |
| 12 | NetworkPolicy (Cilium) | pod in `default` cannot reach erp-core (timeout); pod in `materialflow-loadgen` on the loadgen node can | measured, n = 1 each |
| 13 | GitHub Actions `cd-azure` | actionlint 1.7.12: 0 findings. Ran on 2026-11-25: the first run failed twice (OIDC subject, then a surge pod that could not be scheduled and was rolled back automatically); after both fixes a push-triggered run deployed commit 815d124 end to end in 6 min 39 s (section 13) | measured, n = 1 successful run |

## Method

- **Where.** Terraform, kubectl and Helm ran on a Linux server (amd64) against the cluster API
  with an Entra ID kubeconfig (`kubelogin`, Azure CLI token). The JARs were built and tested on an
  Apple Silicon Mac (`mvn -B -ntp verify`: 85 tests, 0 failures, BUILD SUCCESS), and the Arm64
  images were built natively there from exactly those JARs (`deploy/docker/Dockerfile.runtime`;
  JAR hashes in [environment.json](environment.json)) and pushed to ACR with a short-lived token.
  Third-party images were mirrored into ACR with `az acr import`. Playwright ran on the Mac
  against the public URL.
- **Order of bring-up.** Terraform apply (16.5 min) -> cert-manager -> platform chart with the
  Let's Encrypt staging issuer (certificate in under 2 min) -> production issuer -> application
  chart (all pods Ready about 70 s after install) -> checks.
- **Runs.** Checks 4, 6 and 7 ran three times: after the first install, after resizing the
  requests (below), and after resetting the database. Check 8 ran three times as listed.

## Environment

Full detail, image digests and tool versions: [environment.json](environment.json).

| | |
|---|---|
| Region | westus (no availability zones) |
| AKS | Free tier, Kubernetes v1.37.1, Azure CNI Overlay + Cilium 1.20.4, Entra ID + Azure RBAC, local accounts off, auto-upgrade off |
| Nodes | 2 x Standard_B2ps_v2 (2 vCPU, 8 GiB, Arm64, 40% baseline) + 1 x Standard_B2pls_v2 (2 vCPU, 4 GiB, 30% baseline, tainted `workload=loadgen`); Azure Linux 3.0, containerd 2.2.4 |
| Ingress | Application routing Gateway API (`approuting-istio`, Istio 1.31.3), static IP + DNS label, cert-manager v1.21.3 |
| PostgreSQL | Flexible Server 17.11, GP Standard_D2ds_v5, 32 GiB, private access, `require_secure_transport=on` |
| In cluster | Kafka 4.1.2 (KRaft, 8 GiB managed disk), Redis 8.2.1, Keycloak 26.3.3, Jaeger 2.10.0 |
| Images | tag `b20261124-1`, linux/arm64 |

## Results

### 1. Static validation of the Terraform

`terraform fmt -check -recursive`, `terraform validate`, `tflint --recursive` (terraform preset
`all` + azurerm ruleset 0.33.0) and `trivy config` (0.76.2), all exit 0.

- TFLint initially reported 6 `azurerm_resources_missing_prevent_destroy` warnings and 10
  undocumented outputs. Outputs got descriptions; the prevent-destroy rule is disabled in
  `.tflint.hcl` with its reason (one-command teardown of a disposable environment).
- Trivy initially reported 9 findings. 3 were fixed in code (`log_connections`,
  `log_checkpoints`, `connection_throttle.enable` set on the server). 6 are accepted inline with
  `#trivy:ignore` and a reason: public API server and not private (GitHub-hosted runners have no
  fixed range; Entra ID + Azure RBAC required), no Azure Monitor (cost), no Azure Policy add-on
  (capacity), platform-managed disk keys, and secret expiry set from `time_offset` (not statically
  resolvable).

### 2. Apply and drift

The reviewed plan created 42 resources (`raw/plan1.txt`, `raw/apply1.txt`), 18:43:34 -> 19:00:04
UTC. Slowest: AKS cluster 4 min 53 s, PostgreSQL 4 min 4 s, the Gateway API add-on update
3 min 31 s, Key Vault 2 min 27 s, the loadgen pool 1 min 50 s. The next plan showed two in-place
changes that Azure had made itself (default pool `upgrade_settings` defaults; the
`Microsoft.Storage` service endpoint Flexible Server adds to its subnet). Both were declared in
code; the plan after that reported no changes.

No password exists in the Terraform state: the PostgreSQL and Keycloak passwords are ephemeral
values written to Key Vault with write-only arguments, and the server reads the PostgreSQL one
back through an ephemeral Key Vault data source (`grep` of the plan and apply output finds only
the ACR's unused `admin_password` attribute, marked sensitive).

### 3-5. Pods, health, HTTPS

- [pods-ready.json](pods-ready.json): 55/55 Ready. Application pods spread over both app nodes;
  nothing but DaemonSets and the probe pod ran on the loadgen node.
- Health was read inside each pod (`wget http://127.0.0.1:<port>/actuator/health`): five `UP`.
- Certificate: `CN=materialflow-erp.westus.cloudapp.azure.com`, issuer Let's Encrypt `YE2`, valid
  2026-11-24 to 2027-02-22. TLS 1.3 / `TLS_AES_256_GCM_SHA384`, ALPN h2. `http://` returns 301 to
  `https://`. Responses carry `Strict-Transport-Security`, the console's CSP and
  `X-Frame-Options: DENY`.
- Keycloak behind Envoy issues `Secure; HttpOnly` cookies (`X-Forwarded-Proto: https` trusted via
  `KC_PROXY_HEADERS=xforwarded`); discovery reports the public issuer
  `https://materialflow-erp.westus.cloudapp.azure.com/idp/realms/materialflow`.
- Not exposed: `/idp/admin/`, `/idp/realms/master/`, `/actuator/prometheus` and `/actuator/env`
  are not routed to a backend; they return the console's SPA shell (`<title>MaterialFlow
  Console</title>`).

### 6. JWT and RBAC through the public URL

`MATERIALFLOW_GATEWAY_URL=https://... MATERIALFLOW_KEYCLOAK_URL=https://.../idp bash tests/security/jwt-rbac-e2e.sh`:
`unauthenticated=401 authorized=201 forbidden=403 cross_tenant=404` in all three runs. The
authorized request also sent spoofed `X-Organization-Id`, `X-Actor-Id`, `X-Roles` and
`X-Warehouse-Scope` headers; the order was created for the token's tenant. The read-only user's
403 carried `errorCode=ACCESS_DENIED`.

`tests/deployment/aws-smoke.sh` (the CD smoke test, cloud-agnostic) also passed: gateway health
`UP`, token issuer equals the public URL, 401 without a token, 404 `RESOURCE_NOT_FOUND` with one,
console deep link and `config.js` served.

### 7 and 9. Qualification smoke and one order end to end

`tests/deployment/k8s-qualification-smoke.py` is the Kubernetes version of
`qualification-smoke.py`: health inside the pods, the order and its Kafka Streams projection
through the public gateway, the synthetic gRPC adapter, PostgreSQL through the in-VNet psql
toolbox, Kafka through `kafka-console-consumer.sh` in the broker pod, and Jaeger. Final run
([k8s-qualification-smoke.json](k8s-qualification-smoke.json), order `a074ce74-...`):

| Hop | Observation |
|---|---|
| API | `POST /api/v1/orders` through Envoy -> edge-gateway -> erp-core, 201 |
| PostgreSQL | `sales_orders` row; one `outbox_events` row `SalesOrderAllocated`, status `PUBLISHED`, `trace_parent` set |
| Kafka | record with the event on `materialflow.domain-events.v1`, partition 0 |
| Consumer | `consumer_inbox` row for the event, consumer `erp-core-backorder-reallocation-v1` |
| Projection | aggregate readable at `/api/v1/projections/aggregates/{id}` |
| Trace | trace `2c9a8741...`: 18 spans from `materialflow-edge-gateway`, `materialflow-erp-core` and `materialflow-event-relay`, including `http post /api/v1/orders`, `sales order database transaction`, `outbox publish`, `materialflow.domain-events.v1 send` and `domain event consume` |

All three runs passed (`raw/k8s-qualification-smoke-{1,2,3}.json`).

### 8. Console end-to-end tests

[playwright-e2e-summary.json](playwright-e2e-summary.json). Run 1 (fresh database): 4/4 passed in
12.7 s. Run 2 reused the same database: the earlier approval test had already allocated the
synthetic Portland cement stock, so new orders were `Backordered` instead of `Allocated` and the
two tests that assert allocation failed; login, read-only enforcement and the CSP test passed.
The suite assumes freshly seeded data (its Compose stack starts from empty volumes). After
dropping and recreating the `materialflow` database (Flyway re-ran 7/7 migrations), run 3 passed
4/4 in 13.3 s. The reset procedure is now in the runbook.

### 10. Database checks for the managed server and the drills

- `flyway_schema_history`: 7 rows, all `success = t`, `installed_by = mfadmin`
  ([flyway-history.tsv](flyway-history.tsv) after the reset; `raw/flyway-history-initial.tsv` before).
- `select version()`: PostgreSQL 17.11. Client sessions use TLS 1.3; a `PGSSLMODE=disable`
  connection is refused (`no pg_hba.conf entry ... no encryption`). All 20 application JDBC
  connections observed in `pg_stat_ssl` were encrypted.
- The administrator is not a superuser (member of `azure_pg_admin`), and can do what the fault
  drills need: inside a rolled-back transaction it created a plpgsql trigger function and an
  `AFTER INSERT` trigger on `consumer_inbox`, took `pg_try_advisory_lock(424242)` (visible in
  `pg_locks`) and released it; no trigger was left behind.
- Databases `materialflow` and `keycloak` both exist; no extension had to be allow-listed.

### 11. Fault-injection switch (`faultInjection.enabled`)

[raw/fault-injection-switch.txt](raw/fault-injection-switch.txt). n = 1 per case; these are
functional checks, not recovery-time measurements.

| Case | Result |
|---|---|
| Switch off, `kubectl exec <erp-core> -- kill -KILL 1` | `pidof java` = 1; kill returned 0; 5 s later restart count 0, Ready: the signal was ignored |
| Switch on, erp-core | `shareProcessNamespace=true`, `SPRING_PROFILES_ACTIVE=fault-injection` (Spring logged one active profile), JVM PID 8. SIGKILL -> container exit 137, restart count 1, same pod name and IP 10.244.1.77; restart observed 3.2 s after the kill (0.5 s polling), Ready again after 19.7 s |
| Switch on, event-relay | shared PID namespace, no profile, JVM PID 7; exit 137, restart count 1, same pod and IP; Ready again after 15.6 s |
| Switch off again | `shareProcessNamespace` removed; erp-core runs without the profile |

### 12. Network policy

From an unlabelled pod in `default`, `wget http://materialflow-erp-core.materialflow:8080/actuator/health`
timed out (5 s). From a pod in `materialflow-loadgen` scheduled on the loadgen node (node selector
and toleration), the same request returned `UP`.

### Capacity finding and the automatic rollback

The first values file requested 25m-150m CPU per JVM. With the add-ons already requesting about
2.4 of the 3.8 allocatable vCPUs (two `istiod` replicas alone request 1 vCPU and 4 GiB but use
about 50 MiB), the first `helm upgrade` could not schedule its surge erp-core pod. Helm 4's
`--rollback-on-failure` rolled the release back to revision 1 after the Deployment's progress
deadline ([raw/helm-history-materialflow.txt](raw/helm-history-materialflow.txt): revision 2
failed, revision 3 = rollback). Requests were then resized from measured idle usage
(`kubectl top`: erp-core 360 MiB, Keycloak 503 MiB, Kafka 427 MiB, about 200 m CPU per node in
total); the next upgrade rolled out in 92 s. At the end of this work the app nodes had 212 m and 331 m CPU and
about 1.0 and 1.5 GiB of memory left to request, while actual use was 165-176 m CPU and
3.1-3.3 GiB per node (derived from `kubectl describe node` and `kubectl top`, `raw/node-allocation-final.txt`).

### 13. Delivery pipeline

`.github/workflows/cd-azure.yml` passes actionlint 1.7.12 (with shellcheck) like the other
workflows. On 2026-11-25 the lead set the repository variables from
`terraform output github_actions_variables` and created the `azure-dev` environment, and the
workflow ran against this environment (measured, GitHub Actions run logs):

| Run | Trigger | Result | Cause and fix |
|---|---|---|---|
| [45531055833](https://github.com/simon-jd/materialflow-erp/actions/runs/45531055833) attempt 1 | `workflow_dispatch` on 2fb0217 | build and test passed; image push failed at `azure/login` | AADSTS700213: the repository uses GitHub's immutable OIDC subjects, so the token said `repo:simon-jd@276495489/materialflow-erp@1368743399:environment:azure-dev`. The federated credential now builds that subject from `github_repository_ids` (Terraform: 1 resource changed, nothing else) |
| [45531055833](https://github.com/simon-jd/materialflow-erp/actions/runs/45531055833) attempt 2 | re-run of failed jobs | images pushed; Helm upgrade failed after 12 min and **rolled back automatically** to the previous revision | a surge edge-gateway pod could not be scheduled (`0/3 nodes are available: 1 Insufficient cpu, 1 Insufficient memory, 1 untolerated taint`; app nodes at 99% CPU and 96% memory requested, mostly AKS add-ons). The chart's rollout strategy is now a value; AKS uses `maxSurge: 0, maxUnavailable: 1` |
| [45533718166](https://github.com/simon-jd/materialflow-erp/actions/runs/45533718166) | push of 815d124 to main | success in 6 min 39 s: build and test 3 min 07 s, Arm64 image push 1 min 28 s, Helm upgrade and public smoke test 1 min 55 s | all six application Deployments then ran image tag `815d124…` with 2/2 or 1/1 ready; an authenticated `GET /api/v1/orders` through the public URL returned 200 |

After the run the lead deleted the `AZURE_CLIENT_ID` repository variable, so pushes skip the
workflow again while the cluster is stopped; setting it again re-enables deployment. The deploy identity's Kubernetes
permissions could not be exercised from here: Azure RBAC refuses impersonation with the `oid`
extra (`kubectl auth can-i --as`), see `raw/cd-identity-can-i.txt`. Derived instead: every kind
the application chart renders (Deployment, Service, ConfigMap, ServiceAccount, NetworkPolicy,
PodDisruptionBudget, HorizontalPodAutoscaler) and Helm's release Secrets are in the data actions
of *Azure Kubernetes Service RBAC Writer*; Gateway API and Secrets Store objects are not, which
is why they live in the operator-installed platform chart.

## Spend

| | Amount | Label |
|---|---:|---|
| Cost Management query (ActualCost, month to date) at 19:39 UTC | $0.00: no rows yet for either resource group (usage data lags by hours) | measured |
| Running time | resource group from 18:43 UTC, VMs from about 18:47, PostgreSQL from about 18:54; still running at 19:41 | measured |
| Spend to 19:41 UTC | about $0.40 (2 x B2ps_v2 + 1 x B2pls_v2 for ~0.9 h, D2ds_v5 for ~0.8 h, disks, IPs, load balancer, registry) | estimated |
| Rate while running | about $0.48-0.53 per hour (runbook cost table) | estimated |
| Rate while stopped (AKS and PostgreSQL stopped) | about $0.05 per hour | estimated |

## Not done

- The first application releases were installed by the operator with the same chart and values
  the workflow uses; `cd-azure` ran later (section 13).
- No load or fault-matrix runs; those belong to the next agent.

## Reproduce

```bash
export KUBECONFIG=~/materialflow-azure/kubeconfig U=https://materialflow-erp.westus.cloudapp.azure.com
MATERIALFLOW_PUBLIC_URL=$U SMOKE_CONSOLE=true bash tests/deployment/aws-smoke.sh
MATERIALFLOW_GATEWAY_URL=$U MATERIALFLOW_KEYCLOAK_URL=$U/idp bash tests/security/jwt-rbac-e2e.sh
python3 tests/deployment/k8s-qualification-smoke.py --public-url $U --order-id <order_id> --output /tmp/q.json
(cd apps/admin-console && npm ci && E2E_BASE_URL=$U npx playwright test e2e/admin-console.spec.ts)
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -c 'select version, success from flyway_schema_history'
kubectl get pods -A
```

`raw/` (git-ignored) holds the plan and apply logs, every run's output and the Playwright JSON.
