#!/usr/bin/env python3
"""Hot-SKU JTL summary: the shared nearest-rank summary plus per-wave spans and status codes.

The hot-SKU plan pauses between waves to stay inside the gateway rate limit, so the overall
throughput includes idle time. Burst throughput divides the samples by the summed wave spans.
"""
import collections
import csv
import gzip
import json
import sys
from pathlib import Path

from summarize_jtl import summarize


def read_rows(path):
    opener = gzip.open if str(path).endswith('.gz') else open
    with opener(path, 'rt', newline='') as source:
        return list(csv.DictReader(source))


def summarize_hot_sku(path, run):
    result = summarize(path, run)
    rows = read_rows(path)
    spans = {}
    for row in rows:
        start = int(row['timeStamp'])
        end = start + int(row['elapsed'])
        first, last, samples = spans.get(row['label'], (start, end, 0))
        spans[row['label']] = (min(first, start), max(last, end), samples + 1)
    waves = [dict(label=label, samples=samples, span_ms=last - first)
             for label, (first, last, samples) in sorted(spans.items(), key=lambda item: item[1][0])]
    busy_ms = sum(wave['span_ms'] for wave in waves)
    if busy_ms <= 0:
        raise ValueError('Waves have no duration')
    result.update(waves=waves, burst_throughput_rps=result['samples'] * 1000 / busy_ms,
                  status_codes=dict(sorted(collections.Counter(row['responseCode'] for row in rows).items())))
    return result


if __name__ == '__main__':
    print(json.dumps(summarize_hot_sku(Path(sys.argv[2]), int(sys.argv[1])), indent=2, allow_nan=False))
