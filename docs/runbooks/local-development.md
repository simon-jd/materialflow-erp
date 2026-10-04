# Local development

Prerequisites are Java 21, Maven 3.9+, Docker Compose and Helm. Build and validate:

```bash
mvn verify
docker compose -f deploy/compose/compose.yml config --quiet
helm lint deploy/helm/materialflow
helm template materialflow deploy/helm/materialflow >/tmp/materialflow.yaml
```

Start the complete synthetic stack:

```bash
docker compose -f deploy/compose/compose.yml up -d --build
docker compose -f deploy/compose/compose.yml ps
curl http://localhost:8081/actuator/health
```

Ports can be overridden with `MATERIALFLOW_*_PORT`; this changes only host
bindings. Set `MATERIALFLOW_SECURITY_ENABLED=true` to exercise the imported local
Keycloak realm. Stop without deleting evidence volumes using `docker compose ...
down`; delete volumes only when a deliberate clean-database drill is required.

## Local kind deployment and resilience check

The checked-in kind profile uses local `materialflow/*:kind` images and the
explicit `kind-materialflow` context:

```bash
kind create cluster --name materialflow --wait 180s
kubectl --context kind-materialflow apply -f deploy/kubernetes/local-kind-dependencies.yaml
helm upgrade --install materialflow deploy/helm/materialflow \
  --namespace materialflow --create-namespace \
  -f deploy/helm/materialflow/values-kind.yaml --wait
tests/failure/kubernetes-rollout.sh
```

Build each application image with `--provenance=false` before `kind load
docker-image`; this avoids kind/containerd import failures caused by BuildKit's
separate local attestation manifest. The regular Compose/release image can retain
its provenance metadata.

## Formal local performance protocol

With security enabled in both application services and the synthetic Keycloak
realm running, execute:

```bash
JMETER_HOME=/tmp/apache-jmeter-5.6.3 \
  tests/performance/run-formal-baseline.sh
```

The runner refuses to overwrite an existing result directory. Defaults are
three independent runs, each with 10 minutes of warm-up and 20 minutes of
measurement at 600 requests/minute. It stores compressed JTLs, per-run JSON,
database snapshots, Docker resource samples, HikariCP samples, and Kafka group
state under the ignored `docs/evidence/**/raw/` tree. Override the duration only
for harness smoke checks, never while calling the output a formal baseline.
