"""Tests for the zero-downtime drill summary (no cluster needed)."""
import io
import json
import unittest

import zero_downtime

START = 1_000_000


def request(offset_ms, status=201, ms=20, error=0):
    return dict(i=offset_ms, t=START + offset_ms, ms=ms, s=status, e=error)


class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        steady = [request(-60_000 + n * 100, ms=20 + n % 10) for n in range(600)]
        disruption = [request(n * 100, ms=40) for n in range(300)]
        disruption[50] = request(5_000, status=502, ms=3)
        disruption[51] = request(5_100, status=0, ms=10_000, error=1050)
        after = [request(30_000 + n * 100, ms=22) for n in range(300)]
        self.requests = steady + disruption + after
        self.events = dict(disruption='rollout-erp-core', disruption_started_ms=START,
                           disruption_finished_ms=START + 30_000)

    def test_windows_split_by_start_time_and_failures_are_classified(self):
        summary = zero_downtime.analyze(self.requests, self.events)
        steady, disruption, after = (summary['windows'][w] for w in ('steady', 'disruption', 'after'))
        self.assertEqual((steady['requests'], disruption['requests'], after['requests']), (600, 300, 300))
        self.assertEqual((disruption['failed'], disruption['non_2xx'], disruption['no_response']), (2, 1, 1))
        self.assertEqual(disruption['status_counts'], {'0': 1, '201': 298, '502': 1})
        self.assertEqual(disruption['error_codes'], {'1050': 1})
        self.assertEqual((steady['p50_ms'], steady['p99_ms']), (24, 29))
        self.assertEqual(disruption['max_ms'], 10_000)
        self.assertEqual(summary['total']['failed'], 2)
        self.assertEqual((summary['first_failure_ms'], summary['last_failure_ms']), (5_000, 5_100))
        self.assertEqual(summary['failures'][0], dict(at_ms=5_000, status=502, error_code=0, wall_ms=3))

    def test_time_to_steady_is_the_first_bucket_after_which_everything_is_steady(self):
        summary = zero_downtime.analyze(self.requests, self.events, bucket_seconds=5, threshold_factor=1.2)
        # Steady p95 is 29 ms, so the limit is 34.8 ms; latency is 40 ms until the disruption finished at 30 s.
        self.assertEqual(summary['steady_threshold_p95_ms'], 29 * 1.2)
        self.assertEqual(summary['time_to_steady_ms'], 30_000)
        self.assertEqual(summary['time_to_steady_after_finish_ms'], 0)
        self.assertEqual(summary['p95_ratio_disruption_to_steady'], round(40 / 29, 2))

    def test_default_buckets_are_15_s_and_the_limit_is_twice_the_steady_p95(self):
        summary = zero_downtime.analyze(self.requests, self.events)
        self.assertEqual((summary['bucket_seconds'], summary['steady_threshold_p95_ms']), (15, 58))
        # 40 ms is within 2 x 29 ms, so only the failures at 5.0-5.1 s keep the first bucket unsteady.
        self.assertEqual(summary['time_to_steady_ms'], 15_000)

    def test_an_undisturbed_run_is_steady_at_once_and_a_bad_tail_never_is(self):
        calm = [request(-60_000 + n * 100) for n in range(1200)]
        self.assertEqual(zero_downtime.analyze(calm, self.events)['time_to_steady_ms'], 0)
        tail = calm + [request(59_000, status=503)]
        self.assertIsNone(zero_downtime.analyze(tail, self.events)['time_to_steady_ms'])

    def test_main_reads_console_lines_and_ignores_anything_else(self):
        lines = ['time="..." level=info msg=started\n'] + [json.dumps(r) + '\n' for r in self.requests[:3]]
        self.assertEqual(len(zero_downtime.read_requests(io.StringIO(''.join(lines)))), 3)

    def test_nearest_rank(self):
        self.assertEqual(zero_downtime.nearest_rank([3, 1, 2, 4], 50), 2)
        self.assertIsNone(zero_downtime.nearest_rank([], 99))


if __name__ == '__main__':
    unittest.main()
