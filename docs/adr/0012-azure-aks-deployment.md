# ADR 0012: Run the cloud test environment on AKS with managed PostgreSQL

Date: 2026-11-24. Status: accepted and **applied** to an Azure for Students subscription
(westus). Verification is in the [platform evidence](../evidence/azure-2026-11-24-platform/README.md);
the [runbook](../runbooks/azure-deploy.md) covers bootstrap, deploy, verify, stop/start and teardown.

## Context

The AWS environment ([ADR 0009](0009-aws-ecs-fargate-deployment.md)) is validated as code but has
never been applied. The owner wants the system tested in a real cloud: the process-kill fault
matrix, failure detection and load tests, not only an offline `terraform validate`. Those drills
work at the pod and process level. They SIGKILL the erp-core or event-relay JVM inside a crash
window, watch the container come back, and use PostgreSQL triggers and advisory locks to park a
JVM at an exact point (`tests/failure/README.md`).

The subscription sets hard limits:

- **Money.** About $97 of student credit, of which the platform and all tests together may use $40.
- **Quota.** 6 regional vCPUs in westus. The x86 families it has quota for (DSv4, DSv3, Bsv2,
  BS, Dv2...) are listed with `NotAvailableForSubscription` for every size in westus (checked with
  the Compute SKUs API on 2026-11-24). The only VM family with both quota and availability is the
  Arm64 **Bpsv2** (Ampere Altra), quota 10.
- **Policy.** Five allowed regions; westus is used.

## Decision

| Concern | Choice | Main alternative |
|---|---|---|
| Compute | AKS, Free tier (no control-plane fee), Kubernetes 1.37 (newest GA in westus), auto-upgrade and node-OS auto-upgrade off | Azure Container Apps |
| Nodes | System/app pool 2 x `Standard_B2ps_v2` (2 vCPU, 8 GiB, Arm64); tainted `workload=loadgen` pool 1 x `Standard_B2pls_v2` (2 vCPU, 4 GiB) for test clients only. 6 vCPUs in total, the full quota | 2 x D2s_v4 + 1 x B2s_v2 (planned, not offered to this subscription) |
| Networking | Azure CNI Overlay with the Cilium data plane; NetworkPolicy enforced (default deny from the chart) | kubenet (retired), Azure CNI with Azure NPM |
| Ingress | Application routing add-on with the Kubernetes Gateway API (`approuting-istio`), a Terraform-owned static IP with the DNS label `materialflow-erp.westus.cloudapp.azure.com`, Let's Encrypt via cert-manager (HTTP-01 through an HTTPRoute) | managed NGINX (support ends 2026-11-30), Application Gateway for Containers |
| Database | Azure Database for PostgreSQL Flexible Server 17, General Purpose `Standard_D2ds_v5`, 32 GiB, private access in a delegated subnet, `require_secure_transport=on`; databases `materialflow` and `keycloak` | PostgreSQL in the cluster |
| Events | Kafka 4.1.2 in the cluster: one KRaft broker+controller as a StatefulSet on a `managed-csi` disk | Azure Event Hubs (Kafka endpoint) |
| Cache | Redis 8.2 in the cluster (rate-limiter buckets only) | Azure Managed Redis |
| Identity | Keycloak 26.3.3 in the cluster under `/idp`, realm baked into the image (same image as AWS) | Entra ID |
| Secrets | Key Vault (RBAC, firewall: AKS subnet + operator IP) read by the Secrets Store CSI driver with workload identity; Terraform writes the generated passwords with write-only arguments, so they never enter the state | Kubernetes Secrets from Terraform outputs |
| Delivery | `cd-azure`: GitHub OIDC to a user-assigned identity (AcrPush; AKS Cluster User; AKS RBAC Writer scoped to the `materialflow` namespace); native Arm64 runner builds the images; `helm upgrade --install --rollback-on-failure` | long-lived service principal secret |
| Budget | `$40`/month budget on the resource group plus one on both resource groups together (the node resource group holds the VMs), notifying the subscription Owner role | none |

### AKS rather than Container Apps

ADR 0009 rejected EKS for AWS mainly on its $0.10/hour control plane. On Azure the AKS Free tier
has no control-plane charge, so that argument does not carry over, and the drills decide:

- **Pod-level fault injection.** A drill must SIGKILL the JVM itself, then observe the same
  container restart. Inside its own PID namespace the JVM is PID 1, and the kernel ignores a
  SIGKILL sent to PID 1 from within that namespace (verified here: `kill -KILL 1` from
  `kubectl exec` left the container running). Kubernetes `shareProcessNamespace: true` makes the
  pause container PID 1, so `kill -KILL <java pid>` works and the kubelet restarts the container in
  place. The chart switch `faultInjection.enabled` sets exactly this for erp-core and event-relay
  and adds erp-core's `fault-injection` Spring profile. Container Apps exposes neither the PID
  namespace setting nor a per-container restart in place.
- **Reuse.** The existing Helm chart, its probes, PDBs and network policy deploy unchanged apart
  from a values file; ECS or Container Apps would need a second deployment description.
- **Kafka Streams and Kafka.** A StatefulSet with a persistent disk is ordinary on AKS.
- **Cost of the choice.** Nodes are paid whether or not pods run, and the operator owns node
  sizing. AKS add-ons are not free in capacity: the two `istiod` replicas of the Gateway API
  add-on request 500m CPU and 2 GiB each (they use about 50 MiB), so application requests are
  sized from measured idle usage ([values-aks.yaml](../../deploy/helm/materialflow/values-aks.yaml)).

### Arm64 burstable nodes

Bpsv2 is the only family this subscription can run in westus (see Context). Arm64 costs nothing
in portability here: every image is multi-arch or built for `linux/arm64` (the AWS work already
targets Graviton), and the local measurements were taken on Apple Silicon. Two caveats for the
test agent:

- **CPU credits.** B2ps_v2 has a 40% baseline (B2pls_v2 30%), banks 48 (36) credits per hour and
  can bank 1,152 (864). Sustained load above the baseline drains credits, after which the VM is
  held at the baseline. Load-test results must state the node type and should watch the
  `CPU Credits Remaining` metric; they are not comparable with the earlier 8-core local runs.
- **No surge capacity.** All 6 vCPUs are in use, so an upgrade cannot add a surge node. The
  loadgen pool uses `max_unavailable = 1`; the system pool must be upgraded by hand (runbook).

### Kafka in the cluster rather than Event Hubs

- Event Hubs exposes the Kafka protocol on Standard and above, but **Kafka Streams and Kafka
  transactions are public preview on Premium and Dedicated only**, and compaction and compression
  are Premium/Dedicated features as well (Microsoft Learn, "Apache Kafka protocol support in Azure
  Event Hubs", updated 2026-02-06). `stream-projection` is a Kafka Streams application and
  erp-core consumes with `isolation.level=read_committed`.
- Premium costs **$1.336 per processing-unit hour** in westus, more than twice this whole
  environment, and Standard ($0.03/TU-hour plus $0.09/hour for the Kafka endpoint) lacks Streams.
- The drills need broker-level control: produce records with crash-gate headers, read raw offsets,
  restart the broker. A broker in the cluster allows all of it with `kafka-console-*` tools.
- Trade-off: one broker with replication factor 1 is a single point of failure and has no
  replicated durability; its data survives pod restarts and rescheduling on the managed disk, not
  the loss of the disk. That matches Compose and kind, where every test so far ran.

### Managed PostgreSQL

PostgreSQL is the source of truth (ADR 0001); it is the one stateful service worth paying for:
automated backups (7 days), TLS enforced by the server, patching, and a private endpoint the
cluster reaches through private DNS. General Purpose D2ds_v5 rather than Burstable B2s keeps
database CPU from being throttled during the concurrency and load tests. The fault drills need
the administrator to create trigger functions and take advisory locks: verified on the server
(create function + trigger + `pg_try_advisory_lock` inside a rolled-back transaction). The server
is the largest single cost ($0.203/hour), so it is stopped together with the cluster when idle.

### Gateway API add-on rather than managed NGINX

Community ingress-nginx was retired in March 2026, and the AKS managed NGINX add-on loses support
on 2026-11-30. The application routing add-on's Gateway API implementation is Microsoft's
successor and is enabled through `azapi_update_resource` (azurerm 4.x has no argument for it).
Application Gateway for Containers would add a per-hour gateway charge and a delegated subnet.
Envoy (Istio) sets `X-Forwarded-Proto: https`, which Keycloak trusts (`KC_PROXY_HEADERS=xforwarded`)
so its cookies are `Secure`; the route strips client-supplied `X-Forwarded-Host`. Only the realm
`materialflow` and Keycloak's static resources are routed; `/idp/admin` and the master realm are not.

## Consequences

- Terraform owns the Azure resources; two Helm releases own the cluster: `materialflow-platform`
  (Kafka, Redis, Keycloak, Jaeger, Gateway, certificates, SecretProviderClass; installed by the
  operator because it creates CRD-backed and cluster-scoped objects) and `materialflow` (the five
  services and the console; deployed by `cd-azure`, whose identity can only write ordinary
  namespaced objects in `materialflow`).
- No secret is stored in Terraform state, Git, GitHub or the Helm release. Rotating means bumping
  `secret_version` and restarting the pods; Keycloak and the services read the new password on
  start.
- `azurerm` does not know the Gateway API add-on fields. An azurerm update of the cluster could
  overwrite them; the `azapi_update_resource` shows a diff on the next plan and re-applies them.
- Estimated cost while running is about $0.53/hour (runbook cost table). Stopping AKS and
  PostgreSQL leaves about $0.05/hour (disks, IPs, registry).
- This is a disposable test environment with synthetic data, one Kafka broker and no zone
  redundancy (westus has no availability zones). It is not a production design.
