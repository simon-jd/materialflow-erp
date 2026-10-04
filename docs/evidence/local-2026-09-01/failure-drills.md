# Failure-drill report

All drills ran against the local Compose topology with synthetic records. The
combined raw log is `/tmp/materialflow-failure-drills-final.log`; SHA-256 is
`d86f5d5dfe361492c816a910ccb47f4bb3a5da4605272bb63ff77be0d0c241db`.

| Drill | Measured result |
|---|---|
| Kafka outage and recovery | one Outbox row pending during outage; recovered after 3 polls |
| Redis fail-closed boundary | sensitive write returned HTTP 503; recovered after Redis restart |
| PostgreSQL lock timeout | client timed out with curl exit 28; idempotent retry succeeded; one completion record |
| OTel Collector outage | ERP Core health stayed HTTP 200; export recovered after restart |
| Kubernetes Core Pod deletion | one Core Pod deleted; replacement became ready; synthetic write succeeded during recovery |
| Kubernetes Core rolling update | 10 of 10 synthetic writes succeeded during rollout; final unavailable replicas 0 |

Exact consolidated output:

```text
recovered=true pending_before=1 polls=3
sensitive_write_status=503 recovered=true
client_timeout_exit=28 idempotent_retry=success completed_records=1
core_health_during_collector_outage=200 recovered=true
pod_restart_recovered=true deleted_pod=materialflow-erp-core-6f59477d8f-dx62r rolling_update_recovered=true successful_during_rollout=10 unavailable=0
```

Reproduction scripts are in `tests/failure/`. They also preserve the fixed Redis
fail-closed policy and PostgreSQL idempotent-retry assertions.

The Kubernetes drill ran against kind v0.32.0 / Kubernetes v1.36.1. Helm release
`materialflow` revision 2 was `deployed`; Core and Gateway each ran two replicas,
and every application Deployment ended Ready and Available. The checked-in
reproduction entry point is `tests/failure/kubernetes-rollout.sh`.

Limitations: Relay crash-before/after-send, consumer crash-before-offset-commit,
deadlock/connection-pool exhaustion, event reordering, and Flyway failure remain
`unknown`.
