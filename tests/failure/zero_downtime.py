#!/usr/bin/env python3
"""Summarize one zero-downtime drill run (tests/failure/kubernetes-zero-downtime.sh).

Input: the per-request JSON lines written by tests/performance/kubernetes/orders-constant-rate.js
({"i", "t" start epoch ms, "ms" wall-clock ms, "s" HTTP status, 0 = no response, "e" k6 error
code}) and the drill's events.json (epoch ms of disruption start and finish). Requests are placed
in a window by their start time:

  steady      the `steady_seconds` before the disruption started
  disruption  from the disruption command until the drill saw it complete
  after       from completion until the load stopped

A request fails unless it got 201. Latency percentiles are nearest-rank over all requests of a
window, failed ones included (a fast 503 is still a failure, counted separately).

Time to steady state: the drill splits the run into `bucket_seconds` buckets. A bucket is steady
when it has no failure and its p95 is at most the steady window's p99. The answer is the start of
the first bucket at or after the disruption start from which every later bucket is steady,
measured from the disruption start; 0 means the disruption never left steady state.
"""
import argparse
import json
import math
import sys
from collections import Counter

SUCCESS = 201


def nearest_rank(values, percentile):
    ordered = sorted(values)
    return ordered[max(math.ceil(len(ordered) * percentile / 100) - 1, 0)] if ordered else None


def read_requests(lines):
    return [json.loads(line) for line in lines if line.strip().startswith('{')]


def window_stats(requests):
    latencies = [r['ms'] for r in requests]
    statuses = Counter(str(r['s']) for r in requests)
    failed = [r for r in requests if r['s'] != SUCCESS]
    return dict(requests=len(requests), failed=len(failed),
                non_2xx=sum(1 for r in failed if r['s'] >= 300),
                no_response=sum(1 for r in failed if r['s'] == 0),
                status_counts=dict(sorted(statuses.items())),
                error_codes=dict(sorted(Counter(str(r['e']) for r in failed if r['e']).items())),
                p50_ms=nearest_rank(latencies, 50), p95_ms=nearest_rank(latencies, 95),
                p99_ms=nearest_rank(latencies, 99), max_ms=max(latencies, default=None))


def buckets(requests, origin_ms, bucket_ms):
    grouped = {}
    for r in requests:
        grouped.setdefault((r['t'] - origin_ms) // bucket_ms, []).append(r)
    return [dict(index=index, start_ms=index * bucket_ms, requests=len(group),
                 failed=sum(1 for r in group if r['s'] != SUCCESS),
                 p95_ms=nearest_rank([r['ms'] for r in group], 95))
            for index, group in sorted(grouped.items())]


def time_to_steady(bucket_list, threshold_ms):
    """Start (ms from the origin) of the first non-negative bucket after which every bucket is
    steady; None if the run ends unsteady."""
    candidate = None
    for bucket in bucket_list:
        if bucket['index'] < 0:
            continue
        steady = bucket['failed'] == 0 and bucket['p95_ms'] is not None and bucket['p95_ms'] <= threshold_ms
        if not steady:
            candidate = None
        elif candidate is None:
            candidate = bucket['start_ms']
    return candidate


def analyze(requests, events, steady_seconds=60, bucket_seconds=5):
    started, finished = events['disruption_started_ms'], events['disruption_finished_ms']
    steady = [r for r in requests if started - steady_seconds * 1000 <= r['t'] < started]
    disruption = [r for r in requests if started <= r['t'] < finished]
    after = [r for r in requests if r['t'] >= finished]
    windows = dict(steady=window_stats(steady), disruption=window_stats(disruption), after=window_stats(after))
    threshold = windows['steady']['p99_ms']
    bucket_list = buckets(requests, started, bucket_seconds * 1000)
    settled = time_to_steady(bucket_list, threshold) if threshold is not None else None
    failures = sorted((r for r in requests if r['s'] != SUCCESS), key=lambda r: r['t'])
    return dict(
        disruption=events.get('disruption'), disruption_duration_ms=finished - started,
        steady_seconds=steady_seconds, bucket_seconds=bucket_seconds, steady_threshold_p95_ms=threshold,
        total=window_stats(requests), windows=windows,
        p95_ratio_disruption_to_steady=ratio(windows['disruption']['p95_ms'], windows['steady']['p95_ms']),
        p99_ratio_disruption_to_steady=ratio(windows['disruption']['p99_ms'], windows['steady']['p99_ms']),
        time_to_steady_ms=settled,
        time_to_steady_after_finish_ms=None if settled is None else max(settled - (finished - started), 0),
        first_failure_ms=failures[0]['t'] - started if failures else None,
        last_failure_ms=failures[-1]['t'] - started if failures else None,
        failures=[dict(at_ms=r['t'] - started, status=r['s'], error_code=r['e'], wall_ms=r['ms'])
                  for r in failures[:200]],
        buckets=bucket_list)


def ratio(numerator, denominator):
    return round(numerator / denominator, 2) if numerator is not None and denominator else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('requests', help='requests.jsonl from orders-constant-rate.js')
    parser.add_argument('events', help='events.json from kubernetes-zero-downtime.sh')
    parser.add_argument('--steady-seconds', type=int, default=60)
    parser.add_argument('--bucket-seconds', type=int, default=5)
    args = parser.parse_args(argv)
    with open(args.requests, encoding='utf-8') as source:
        requests = read_requests(source)
    with open(args.events, encoding='utf-8') as source:
        events = json.load(source)
    json.dump(analyze(requests, events, args.steady_seconds, args.bucket_seconds), sys.stdout, indent=2)
    print()


if __name__ == '__main__':
    main()
