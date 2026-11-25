# ADR 0011: Detect a dead domain-event consumer in 10 seconds

Date: 2026-11-24. Status: implemented; measured locally (see Verification).

## Problem

The [process-kill fault matrix](../evidence/local-2026-11-23-fault-matrix/README.md)
SIGKILLed erp-core 15 times inside its three consumer crash windows. The container
restarted in under a second and Spring started in 6–12 s, yet recovery took 42–47 s
every time. erp-core's consumer used the classic group protocol with Kafka's default
`session.timeout.ms` of 45 s and no `group.instance.id`. A restarted JVM joins as a
new member, and the coordinator reassigns the dead member's partition only after
that member's session expires. Correctness was never at risk (the inbox and the
offset contract held in all runs), but the partition sat unprocessed for 45 s.

## Decision

- `materialflow.kafka.consumer.failure-detection` sets `session-timeout`,
  `heartbeat-interval` and `group-instance-id`, overridable through
  `MATERIALFLOW_KAFKA_CONSUMER_SESSION_TIMEOUT`, `..._HEARTBEAT_INTERVAL` and
  `..._GROUP_INSTANCE_ID`. A `DefaultKafkaConsumerFactoryCustomizer` applies them
  to the Boot-managed consumer factory. A heartbeat that is not shorter than the
  session timeout fails startup.
- Defaults: 10 s session timeout and 3 s heartbeat (Kafka's guidance is a heartbeat
  of at most a third of the timeout). The broker rejects anything below its
  `group.min.session.timeout.ms`, 6 s by default, so going lower would also need a
  broker change.
- Static membership stays off by default (blank `group-instance-id`).

## Trade-offs

**Shorter session timeout: risk of false rebalances.** The consumer heartbeats from a
background thread, so slow record processing is governed by `max.poll.interval.ms`
(300 s), not by the session timeout. What can still evict a live member is anything
that stops the whole JVM or its network for longer than about 10 s: a long
stop-the-world GC pause, CPU-quota throttling of a busy 2-CPU container, a paused VM
or a network stall. An evicted member's next offset commit fails, its in-flight
record is redelivered to whichever member gets the partition, and the inbox absorbs
the duplicate. So the cost is a rebalance pause and repeated work, not a duplicated
business effect. Watch the rebalance rate (`kafka_consumer_coordinator_rebalance_total`)
and raise the timeout with the environment variable if rebalances follow GC or
throttling. 10 s is a compromise: about four times faster detection than 45 s,
with headroom over typical G1 pauses on a 1 GiB heap. Pause times were not measured
in this work.

**Static membership: fast restart of the same replica, slow replacement of a
different one.** A member that rejoins with the same `group.instance.id` takes back
its assignment without waiting for the session timeout and without a rebalance.
But static members do not leave the group when they stop. A Kubernetes Deployment
pod or an ECS task gets a new identity on every rollout, so the old identity keeps
its partitions until its session expires, once per replaced replica. Two replicas
configured with the same identity fence each other. Static membership therefore
suits only replicas with a stable, unique identity that survives restarts and
rollouts, such as a StatefulSet ordinal or a single named Compose container. It
must not be set globally in a Deployment or ECS task definition.

**Alternatives not taken.** The KIP-848 consumer protocol (`group.protocol=consumer`)
moves the session timeout to the broker (`group.consumer.session.timeout.ms`, also
45 s by default) and would need a broker-side change plus re-qualification of the
listener container. A cooperative assignor reduces disruption to other members
during a rebalance but does not detect a dead member any sooner. Neither changes
the at-least-once contract, so neither was needed for this fix.

## Verification

`ConsumerFailureDetectionConfigurationTest` loads the real `application.yml` and
checks the defaults, the environment overrides, static membership on request and
the startup failure for an invalid heartbeat.

The fault matrix was re-run on images built from this change: 15 SIGKILLs in
windows 1, 2 and 4 with the new default, and 15 with static membership (one Compose
container that keeps `erp-core-0` across `docker start`). The original 20-kill run
at Kafka's 45 s default is the baseline. Local, synthetic, single-broker numbers;
recovery is measured from `docker kill` to the first observation of the restored
invariant:

| Window | Kafka default, 45 s (baseline) | 10 s, dynamic membership | 10 s + static membership |
|---|---:|---:|---:|
| 1 `consumer-pre-commit` | 45.2 s (43.8–47.0) | 10.7 s (9.0–11.5) | 8.7 s (8.6–9.5) |
| 2 `consumer-post-commit` | 45.3 s (42.4–46.0) | 7.9 s (7.8–11.4) | 7.9 s (7.9–9.9) |
| 4 `dlq-post-ack` | 45.3 s (43.8–45.7) | 11.1 s (9.3–11.5) | 9.4 s (9.4–9.9) |
| All 15 consumer kills, p50 | 45.3 s | 10.7 s | 9.2 s |
| Runs passed | 15/15 | 15/15 | 15/15 |

Cells are p50 with min–max over 5 runs (measured per run, p50 derived by nearest
rank). The window invariants held in all 45 runs. With 10 s, recovery is close to
the JVM's own restart (Spring started in 5.9–6.8 s in the static run) plus the
group join. Static membership removed the remaining wait for the dead member's
session and narrowed the spread, but its gain at p50 (about 1.5 s) is within the
roughly 3 s resolution of the drill's offset probes, so the data supports "about
as fast as a restart" rather than a precise saving. Spring Kafka appends the
listener container index to a configured identity, so `erp-core-0` registered as
`erp-core-0-0`; it was still the same identity across every restart.

These are observations from one workstation, not a recovery SLA. Production
timeouts must be chosen against measured GC pauses and CPU throttling on the
target platform.

## Note, 2026-11-24: two replicas on AKS

The same drill ran on AKS with two erp-core replicas and the kubelet restarting the killed
container ([evidence](../evidence/azure-2026-11-24-fault-matrix/README.md)). All 15 consumer-window
kills recovered in 9.8–16.4 s, every time on the *surviving* replica once the killed member's 10 s
session had expired, while the kubelet's crash back-off held the killed container's restart back
for 0.5 to 325 s. In a Deployment with two or more replicas, failure detection, not the restart,
bounds consumer recovery, which is the case this ADR's 10 s default was chosen for. It also
confirms that static membership would bring nothing in a Deployment: the same pod rejoins only
after its back-off, long after its session (and its claim on the partition) has expired. The
decision stands.
