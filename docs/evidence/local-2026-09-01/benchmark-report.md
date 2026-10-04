# Performance and benchmark report

## Formal authorization-enabled REST write baseline

The charter protocol was executed in full from `2026-09-02T04:59:28Z` through
`2026-09-02T06:29:46Z`: three independent sequential runs, each with 10 minutes
of warm-up followed by 20 minutes of measurement. Four JMeter threads used a
30-second ramp and a target arrival rate of 600 requests/minute against the
Gateway. Every request carried a Keycloak-signed JWT and a unique idempotency
key.

| Run | Warm-up | Measurement | Samples | Errors / timeouts | Throughput | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 min | 20 min | 12,003 | 0 / 0 | 10.003 req/s | 19 ms | 37 ms | 46 ms |
| 2 | 10 min | 20 min | 12,003 | 0 / 0 | 10.003 req/s | 20 ms | 36 ms | 44 ms |
| 3 | 10 min | 20 min | 12,003 | 0 / 0 | 10.003 req/s | 19 ms | 35 ms | 43 ms |
| **Three-run median / mean rate** | **10 min/run** | **20 min/run** | **36,009 total** | **0 / 0** | **10.003 req/s** | **19 ms** | **36 ms** | **44 ms** |

The measured write path satisfies the charter's initial local target of error
rate below 0.5%, p95 below 500 ms, and p99 below 1 second at this explicitly
bounded arrival rate. This is a baseline at 10 requests/second, not a saturation
or maximum-capacity claim.

### Workload and dataset

- 100% synthetic `POST /api/v1/orders` writes; no read traffic.
- One deliberately hot material/warehouse/location combination, one line per
  order, quantity `0.001`.
- PostgreSQL order count grew from 1,392 to 55,410. The delta of 54,018 equals
  all three warm-up and measurement phases.
- Compose topology: one Gateway, Core, Relay, Kafka, PostgreSQL, Redis, and
  Projection instance; tracing sampling probability remained 1.0.
- Each phase obtained a fresh synthetic Keycloak token. The checked-in local
  realm uses a two-hour test-token lifespan only to avoid mid-measurement expiry.

### Database, pool, resource, and Kafka observations

The monitor captured 114 samples at roughly 30-second intervals. HikariCP active
connections ranged from 0 to 2 at sampling instants. PostgreSQL reported zero
lock waiters in 113 samples and one transient waiter in one sample; the next
sample and every run-end snapshot returned zero. Deadlock count remained zero.

| Container | Observed CPU range |
|---|---:|
| Edge Gateway | 2.27%–17.16% |
| ERP Core | 3.02%–21.41% |
| Event Relay | 0.30%–4.18% |
| PostgreSQL | 3.67%–29.32% |
| Kafka | 1.23%–186.23% |

Docker CPU percentages may exceed 100% on this 32-logical-CPU host. At every
run end, Outbox pending was zero, both Kafka consumer groups had lag zero, and
PostgreSQL had zero current lock waiters. Final invariant checks also returned
zero invalid inventory rows, over-credit accounts, and duplicate-invoice orders.

### Environment and limitations

Host: Intel Core i9-13900K, 24 cores / 32 logical CPUs, 123 GiB RAM, Linux
`7.0.0-29-generic`, Temurin JDK 21.0.12, JMeter 5.6.3. The raw environment file
contains the complete Compose process inventory.

This is a single-host local baseline with a synthetic dataset, no external
network latency, no mixed read/write workload, no multi-node Kafka/PostgreSQL,
and no saturation search. It supports a reproducible engineering claim at the
tested rate only; it does not establish a production SLA or cloud capacity.

Raw evidence lives in the Git-ignored directory
`raw/performance/formal-2026-09-02/`. Important SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| Aggregate JSON | `a7d3a7ee2bd9bde0ca7a5af2c1c9b489c4c8084237200f104707344c47f7e2a1` |
| Run 1 measurement JTL (gzip) | `1c07e8783be1ea19db86dd90af579cc69a2753ddf29b1eef7f745e6c9a649ef0` |
| Run 2 measurement JTL (gzip) | `185ef2166c5f69bd54a13a031aa082d44a7cc486706a2d4bfc056dd45986b5a9` |
| Run 3 measurement JTL (gzip) | `4be85b8b40ccc06f55003d121937048a786908415165fc30215e7fa7f654f77c` |

## JMH microbenchmark smoke

One fork used two one-second warm-up iterations and three one-second measurement
iterations:

| Benchmark | Average | Error |
|---|---:|---:|
| Two-of-three approval rule | 56.394 ns/op | ±35.962 ns/op |
| Contract price selection/snapshot | 45.688 ns/op | ±9.715 ns/op |
| Domain-event serialization | 478.858 ns/op | ±86.208 ns/op |

Raw JSON SHA-256:
`d0ef27cbad9724cef989b3bae9a9c5275237074fdc8d1e32b6dd7fee226e8f06`.
