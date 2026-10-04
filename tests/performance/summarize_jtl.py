#!/usr/bin/env python3
"""CSV-aware nearest-rank JMeter summaries; timestamps may finish out of order."""
import csv
import gzip
import json
import math
import statistics
import sys
from pathlib import Path


def summarize(path, run):
    opener = gzip.open if str(path).endswith('.gz') else open
    with opener(path, 'rt', newline='') as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise ValueError('JTL contains no samples')
    elapsed = sorted(int(row['elapsed']) for row in rows)
    starts = [int(row['timeStamp']) for row in rows]
    ends = [int(row['timeStamp']) + int(row['elapsed']) for row in rows]
    duration_ms = max(ends) - min(starts)
    if min(elapsed) < 0 or duration_ms <= 0:
        raise ValueError('Invalid sample timing')
    if any(row['success'] not in ('true', 'false') for row in rows):
        raise ValueError('Invalid success flag')
    errors = sum(row['success'] != 'true' or row['responseCode'] != '201' for row in rows)
    timeouts = sum(row['responseCode'] in ('408', '504') or
                   any(x in row.get('responseMessage', '').lower() for x in ('timeout', 'timed out'))
                   for row in rows)
    total = len(rows)
    return dict(run=run, samples=total, successes=total-errors, errors=errors, timeouts=timeouts,
                duration_ms=duration_ms, first_sample_ms=min(starts), last_completion_ms=max(ends),
                p50_ms=elapsed[math.ceil(total*.50)-1], p95_ms=elapsed[math.ceil(total*.95)-1],
                p99_ms=elapsed[math.ceil(total*.99)-1], throughput_rps=total*1000/duration_ms,
                error_rate_percent=100*errors/total, timeout_rate_percent=100*timeouts/total)


def aggregate(runs):
    if not runs:
        raise ValueError('No runs')
    total = sum(r['samples'] for r in runs)
    return dict(runs=runs, aggregate=dict(
        samples=total, errors=sum(r['errors'] for r in runs),
        p50_ms=statistics.median(r['p50_ms'] for r in runs),
        p95_ms=statistics.median(r['p95_ms'] for r in runs),
        p99_ms=statistics.median(r['p99_ms'] for r in runs),
        throughput_rps=total*1000/sum(r['duration_ms'] for r in runs),
        error_rate_percent=100*sum(r['errors'] for r in runs)/total,
        timeout_rate_percent=100*sum(r['timeouts'] for r in runs)/total))


if __name__ == '__main__':
    if sys.argv[1] == 'aggregate':
        result = aggregate([json.loads(Path(p).read_text()) for p in sys.argv[2:]])
    else:
        result = summarize(sys.argv[2], int(sys.argv[1]))
    print(json.dumps(result, indent=2, allow_nan=False))
