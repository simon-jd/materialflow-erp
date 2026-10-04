# Controlled failure drills

Run these scripts only against the local synthetic Compose stack. They stop and
restart named project services, write synthetic orders, and leave raw output in
`/tmp`. Each script fails unless its recovery invariant is observed.

```bash
tests/failure/kafka-outage-recovery.sh
tests/failure/redis-fail-closed.sh
tests/failure/postgres-lock-timeout.sh
tests/failure/otel-collector-outage.sh
tests/failure/kubernetes-rollout.sh
```

The Kafka pre/post-send and consumer post-commit/pre-offset crash windows are
covered deterministically by Relay and Inbox tests. The Kubernetes drill selects
only the explicit disposable `kind-materialflow` context, deletes one ERP Core
Pod, and submits synthetic transactions during a forced rolling update. It never
selects or mutates an arbitrary current cluster.
