# Azure AKS dev environment: bootstrap, deploy, verify, stop/start, teardown

The environment in `deploy/terraform/azure` runs the five services, the admin console, Kafka,
Redis, Keycloak and Jaeger on AKS (Arm64 nodes), with PostgreSQL 17 on Azure Database for
PostgreSQL Flexible Server, behind one public HTTPS origin. Design reasons are in
[ADR 0012](../adr/0012-azure-aks-deployment.md); the first bring-up is recorded in the
[platform evidence](../evidence/azure-2026-11-24-platform/README.md).

> It is billable while it runs (about $0.53/hour, see [Cost](#cost)). Stop it when idle and destroy
> it when the tests are done.

## Architecture

```mermaid
flowchart LR
    viewer([Browser / test client]) -->|"HTTPS materialflow-erp.westus.cloudapp.azure.com"| pip[Static public IP + DNS label]
    gha([GitHub Actions cd-azure]) -->|OIDC, environment azure-dev| uami[User-assigned identity]
    uami -.->|AcrPush| acr[(ACR Basic)]
    uami -.->|"helm upgrade (RBAC Writer, ns materialflow)"| apps
    le([Let's Encrypt]) -.->|HTTP-01| gw

    pip --> gw
    subgraph vnet["VNet 10.42.0.0/16 (westus)"]
        subgraph aks["AKS Free tier 1.37, Azure CNI Overlay + Cilium"]
            subgraph edge["ns materialflow-edge"]
                gw["Gateway (approuting-istio, Envoy x2)<br/>TLS: cert-manager"]
            end
            subgraph apps["ns materialflow (app pool: 2 x B2ps_v2 Arm64)"]
                console[admin-console x2]
                egw[edge-gateway x2]
                core[erp-core x2]
                relay[event-relay x2]
                proj[stream-projection]
                adapter[integration-adapter]
                kc[Keycloak /idp]
                kafka[("Kafka 4.1.2 KRaft<br/>managed-csi disk")]
                redis[(Redis)]
                jaeger[(Jaeger)]
                toolbox[psql toolbox]
            end
            subgraph lg["ns materialflow-loadgen (tainted pool: 1 x B2pls_v2)"]
                clients[test clients]
            end
            csi[Secrets Store CSI driver]
        end
        pg[("PostgreSQL Flexible Server 17<br/>D2ds_v5, private access<br/>DBs materialflow + keycloak")]
    end
    kv[(Key Vault)]

    gw -->|"/"| console
    gw -->|"/api, /actuator/health"| egw
    gw -->|"/idp/realms/materialflow, /idp/resources"| kc
    egw --> core
    egw --> proj
    egw -->|JWKS in-cluster| kc
    egw --> redis
    core --> pg
    core --> redis
    relay --> pg
    relay --> kafka
    kafka --> core
    kafka --> proj
    kc --> pg
    csi -->|workload identity| kv
    acr -.->|AcrPull, kubelet identity| aks
```

| Item | Value |
|---|---|
| Public URL | `https://materialflow-erp.westus.cloudapp.azure.com` (console `/`, API `/api`, Keycloak `/idp`) |
| Resource groups | `rg-materialflow-dev` (everything Terraform creates), `rg-materialflow-dev-nodes` (AKS-managed: VMs, disks, load balancer) |
| Cluster | `aks-materialflow-dev`, Entra ID + Azure RBAC, local accounts disabled |
| Namespaces | `materialflow` (apps, data services), `materialflow-edge` (Gateway, certificate), `materialflow-loadgen` (test clients), `cert-manager` |
| Helm releases | `materialflow-platform` (operator), `materialflow` (cd-azure), `cert-manager` (operator) |

## Prerequisites

- Terraform >= 1.11 (validated with 1.17.2), Azure CLI >= 2.86, kubectl and kubelogin
  (`az aks install-cli`), Helm 4 (validated with 4.3.2), Docker with Buildx for `linux/arm64`
  images (an Arm64 machine builds them natively), `jq`.
- Owner (or Contributor + User Access Administrator) on the subscription: Terraform creates role
  assignments.

## Bootstrap (one time)

All commands run from the repository root unless noted. `KUBECONFIG` points at a dedicated file so
the operator's default kubeconfig is never touched.

```bash
export ARM_SUBSCRIPTION_ID=<subscription-id>        # also passed explicitly in dev.tfvars
export KUBECONFIG=$HOME/materialflow-azure/kubeconfig
mkdir -p ~/materialflow-azure/state && chmod 700 ~/materialflow-azure ~/materialflow-azure/state
```

1. **Check capacity** (the subscription may not be offered every size; this is why the nodes are Arm64):

   ```bash
   az vm list-usage -l westus --subscription "$ARM_SUBSCRIPTION_ID" -o table | grep -E 'Total Regional|Bpsv2'
   az rest --method get --url "https://management.azure.com/subscriptions/$ARM_SUBSCRIPTION_ID/providers/Microsoft.Compute/skus?api-version=2021-07-01&\$filter=location%20eq%20'westus'" \
     | jq -r '.value[] | select(.name=="Standard_B2ps_v2" or .name=="Standard_B2pls_v2") | "\(.name) restrictions=\(.restrictions|length)"'
   ```

2. **Terraform.** `dev.tfvars` is git-ignored; copy the example and set the subscription and the
   public IPv4 address of the machine that runs Terraform (Key Vault firewall).

   ```bash
   cd deploy/terraform/azure
   cp dev.tfvars.example dev.tfvars
   terraform init -backend-config="path=$HOME/materialflow-azure/state/terraform.tfstate"
   terraform fmt -check -recursive && terraform validate
   tflint --init && tflint --recursive --config "$PWD/.tflint.hcl"
   trivy config --skip-dirs .terraform --exit-code 1 .
   terraform plan -var-file=dev.tfvars -out=$HOME/materialflow-azure/state/apply.tfplan
   terraform apply $HOME/materialflow-azure/state/apply.tfplan   # about 15 minutes
   terraform output -raw helm_environment_values > ~/materialflow-azure/environment-values.yaml
   eval "$(terraform output -raw get_credentials_command)"
   cd -
   ```

3. **Images** for the first release (later releases come from `cd-azure`). Package the tested JARs,
   then build `linux/arm64` images and push them with a short-lived ACR token:

   ```bash
   mvn -B -ntp verify
   ACR=$(terraform -chdir=deploy/terraform/azure output -raw acr_login_server); TAG=<tag>
   az acr login -n "${ACR%%.*}"
   bash tests/deployment/build-runtime-images.sh "$TAG"     # or the loop in cd-azure.yml
   for app in erp-core event-relay edge-gateway stream-projection integration-adapter; do
     docker tag "materialflow-qualification/$app:$TAG" "$ACR/materialflow/$app:$TAG"
     docker push "$ACR/materialflow/$app:$TAG"
   done
   docker buildx build --platform linux/arm64 --push -t "$ACR/materialflow/admin-console:$TAG" apps/admin-console
   docker buildx build --platform linux/arm64 --push -f deploy/docker/Dockerfile.keycloak -t "$ACR/materialflow/keycloak:$TAG" .
   # Third-party images are mirrored into the registry (no Docker Hub pulls from the nodes):
   for src in apache/kafka:4.1.2 library/redis:8.2.1-alpine jaegertracing/jaeger:2.10.0 library/postgres:17.11-alpine; do
     az acr import -n "${ACR%%.*}" --source "docker.io/$src" --image "mirror/${src#library/}" --force
   done
   ```

4. **cert-manager, platform, application:**

   ```bash
   helm upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version v1.21.3 \
     -n cert-manager --create-namespace -f deploy/kubernetes/aks/cert-manager-values.yaml --wait
   kubectl create namespace materialflow
   # letsencrypt-staging first if the DNS name or port 80 path is new; then letsencrypt-prod.
   helm upgrade --install materialflow-platform deploy/kubernetes/aks/platform -n materialflow \
     -f ~/materialflow-azure/environment-values.yaml --set keycloak.imageTag="$TAG" \
     --set tls.issuer=letsencrypt-prod --timeout 12m
   kubectl -n materialflow-edge wait --for=condition=Ready certificate/materialflow-tls --timeout=5m
   helm upgrade --install materialflow deploy/helm/materialflow -n materialflow \
     -f deploy/helm/materialflow/values-aks.yaml -f ~/materialflow-azure/environment-values.yaml \
     --set global.imageTag="$TAG" --rollback-on-failure --wait --timeout 12m
   ```

5. **GitHub** (repository admin). Create the `azure-dev` environment (restrict it to `main` if
   wanted) and set the variables from `terraform output -json github_actions_variables`
   (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AKS_RESOURCE_GROUP`,
   `AKS_CLUSTER_NAME`, `ACR_NAME`, `MATERIALFLOW_PUBLIC_URL`, `AKS_HELM_VALUES`). None is secret.
   `AZURE_CLIENT_ID` must be a repository variable: the job-level gate reads it before any
   environment is entered. The federated credential trusts exactly
   `repo:simon-jd/materialflow-erp:environment:azure-dev` (`terraform output github_oidc_subject`).

## Deploy (every push to main)

`cd-azure` runs three jobs: `mvn -B -ntp verify` and upload of the five tested JARs; `linux/arm64`
images built on a native Arm64 runner from exactly those JARs plus the console, tagged with the
commit SHA and pushed with an OIDC login (AcrPush only); `helm upgrade --install` of the
application chart with `--rollback-on-failure` (Helm 4's name for `--atomic`), then
`tests/deployment/aws-smoke.sh` against the public URL (gateway health, Keycloak token whose
issuer is the public URL, 401 without a token, 404 `RESOURCE_NOT_FOUND` with one, console deep
link and `config.js`). The deploy identity can write ordinary objects in `materialflow` only; the
platform chart, Gateway and certificates are an operator change.

Rollback of a healthy but wrong release: `helm -n materialflow rollback materialflow <revision>`.

## Verify

```bash
H=https://materialflow-erp.westus.cloudapp.azure.com
kubectl get pods -A                                     # all Running/Ready
MATERIALFLOW_PUBLIC_URL=$H SMOKE_CONSOLE=true bash tests/deployment/aws-smoke.sh
MATERIALFLOW_GATEWAY_URL=$H MATERIALFLOW_KEYCLOAK_URL=$H/idp bash tests/security/jwt-rbac-e2e.sh
python3 tests/deployment/k8s-qualification-smoke.py --public-url $H --order-id <id from the line above> --output /tmp/q.json
(cd apps/admin-console && npm ci && E2E_BASE_URL=$H npx playwright test e2e/admin-console.spec.ts)
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -c 'select version, success from flyway_schema_history'
```

**Getting a JWT** (synthetic users from `deploy/compose/keycloak/materialflow-realm.json`):

```bash
TOKEN=$(curl -fsS "$H/idp/realms/materialflow/protocol/openid-connect/token" \
  -d client_id=materialflow-cli -d grant_type=password \
  -d username=synthetic-demo -d password=synthetic-demo-only | jq -r .access_token)
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/v1/orders?size=5"
```

Access tokens live 2 hours (`accessTokenLifespan: 7200` in the realm). `synthetic-sales` is a maker, `synthetic-demo` has every
role, `synthetic-readonly` has none, `synthetic-other-tenant` belongs to another organization.

**Inside the cluster.** Services: `materialflow-erp-core:8080`, `materialflow-edge-gateway:8081`,
`materialflow-stream-projection:8082`, `materialflow-integration-adapter:8083` (gRPC 9090),
`materialflow-event-relay:8084`, `materialflow-admin-console:8080`, `keycloak:8080` (management
9000), `kafka:9092`, `redis:6379`, `jaeger:16686` (OTLP 4318). The default-deny NetworkPolicy
admits only pods labelled `app.kubernetes.io/part-of=materialflow`, the Gateway namespace and
the `materialflow-loadgen` namespace. Test clients go to `materialflow-loadgen` with
`nodeSelector: {workload: loadgen}` and a toleration for `workload=loadgen:NoSchedule`.

```bash
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql                 # admin psql over TLS
kubectl -n materialflow exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic materialflow.domain-events.v1 --from-beginning --timeout-ms 10000
kubectl -n materialflow port-forward deploy/jaeger 16686    # traces; port-forward bypasses NetworkPolicy
```

## Fault drills

`faultInjection.enabled=true` (application chart) gives erp-core and event-relay
`shareProcessNamespace: true` and runs erp-core with the `fault-injection` Spring profile
(`ConsumerCrashWindowGate`). Enable it only for a drill:

```bash
helm upgrade materialflow deploy/helm/materialflow -n materialflow --reuse-values --set faultInjection.enabled=true --wait
POD=$(kubectl -n materialflow get pod -l app.kubernetes.io/name=materialflow-erp-core -o name | head -1)
kubectl -n materialflow exec "$POD" -- sh -c 'kill -KILL $(pidof java)'     # container restarts in place
```

The kubelet restarts a killed container immediately the first time; later restarts wait for the
crash back-off (10 s doubling to 300 s) unless the container ran longer than the current back-off
since its last restart, and the back-off resets after 10 minutes without a restart. Space repeated
kills accordingly or record restart delay separately from recovery. Turn the switch off afterwards
(`--set faultInjection.enabled=false`).

## Reset synthetic data

The Playwright suite and the drills assume freshly seeded data (for example, stock that the
approval test allocates). To start over, recreate the application database; erp-core re-runs the
Flyway migrations, including the synthetic seed, when it starts:

```bash
kubectl -n materialflow scale deploy materialflow-erp-core materialflow-event-relay --replicas=0
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -d postgres -c 'DROP DATABASE materialflow WITH (FORCE)'
kubectl -n materialflow exec deploy/materialflow-toolbox -- psql -d postgres \
  -c "CREATE DATABASE materialflow ENCODING 'UTF8' LC_COLLATE 'en_US.utf8' LC_CTYPE 'en_US.utf8' TEMPLATE template0"
kubectl -n materialflow scale deploy materialflow-erp-core materialflow-event-relay --replicas=2
kubectl -n materialflow rollout status deploy/materialflow-erp-core
```

Kafka keeps the old events and the consumer groups keep their committed offsets, so old events
are not reprocessed; the stream projection keeps aggregates of the old orders (harmless).

## Stop and start (save credit)

Stopping deallocates the VMs and the database compute; disks, IPs, the registry and Key Vault keep
billing (about $0.05/hour). Do this whenever the environment will be idle for more than an hour.

```bash
S=--subscription=$ARM_SUBSCRIPTION_ID
az aks stop $S -g rg-materialflow-dev -n aks-materialflow-dev
az postgres flexible-server stop $S -g rg-materialflow-dev -n "$(terraform -chdir=deploy/terraform/azure output -raw postgres_server_name)"
# later
az postgres flexible-server start $S -g rg-materialflow-dev -n <server>
az aks start $S -g rg-materialflow-dev -n aks-materialflow-dev   # about 5 minutes; pods come back by themselves
```

A stopped Flexible Server starts again automatically after 7 days. Start PostgreSQL before AKS, or
restart Keycloak and erp-core afterwards if they gave up connecting.

## Upgrades

Auto-upgrade is off because a surge node does not fit the 6-vCPU quota. To upgrade deliberately:
`az aks nodepool update --max-surge 0 --max-unavailable 1` on each pool, then
`az aks upgrade --kubernetes-version <v>`; afterwards set `kubernetes_version` in Terraform.

## Teardown

```bash
helm -n materialflow uninstall materialflow materialflow-platform   # releases the Kafka disk and LB rules
terraform -chdir=deploy/terraform/azure destroy -var-file=dev.tfvars
az group list --subscription "$ARM_SUBSCRIPTION_ID" --query "[?starts_with(name,'rg-materialflow')].name"   # expect []
```

Key Vault is purged on destroy (purge protection is off), so the name can be reused. Budgets are
deleted with the stack. Terraform state remains in `~/materialflow-azure/state`.

## Cost

**Estimated** from westus pay-as-you-go list prices (Azure Retail Prices API, 2026-11-24), Linux,
before tax and credits. Measured spend is in the evidence README.

| Component | Sizing | Unit price | $/hour running | $/hour stopped |
|---|---|---:|---:|---:|
| AKS control plane | Free tier | $0 | 0 | 0 |
| App node pool | 2 x Standard_B2ps_v2 | $0.08 / VM-hour | 0.160 | 0 |
| Load-generator pool | 1 x Standard_B2pls_v2 | $0.04 / VM-hour | 0.040 | 0 |
| OS disks | 3 x 32 GiB Premium SSD (P4) | $5.28 / month | 0.022 | 0.022 |
| Kafka disk | 8 GiB Standard SSD (E2) | $0.60 / month | 0.001 | 0.001 |
| PostgreSQL compute | GP Standard_D2ds_v5 | $0.203 / hour | 0.203 | 0 |
| PostgreSQL storage | 32 GiB (P4) | about $0.12 / GB-month | 0.005 | 0.005 |
| Public IPs | ingress + AKS outbound (Standard) | $0.005 / hour | 0.010 | 0.010 |
| Load balancer | Standard, a few rules | about $0.025 / hour | 0.025 | 0 |
| Container registry | Basic | $0.1666 / day | 0.007 | 0.007 |
| Key Vault, DNS, bandwidth | demo traffic | per operation | < 0.005 | ~0 |
| **Total** | | | **≈ 0.48 – 0.53** | **≈ 0.05** |

The $40 budget therefore buys roughly 75 running hours. The load-generator node can be scaled to
zero between tests (`loadgen_node_count = 0`, saves $0.04/hour).
