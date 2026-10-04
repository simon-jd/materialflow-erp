import tempfile
import unittest
from pathlib import Path

from summarize_jtl import aggregate, summarize


class SummaryTest(unittest.TestCase):
    def test_quoted_csv_out_of_order_completion_and_unexpected_status(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'input.jtl'
            path.write_text('timeStamp,elapsed,responseCode,responseMessage,success\n'
                            '1000,500,201,"Created, OK",true\n'
                            '1100,50,200,OK,true\n')
            result = summarize(path, 1)
        self.assertEqual(result['duration_ms'], 500)
        self.assertEqual(result['errors'], 1)
        self.assertEqual(result['p99_ms'], 500)
        self.assertEqual(result['throughput_rps'], 4)

    def test_empty_file_is_not_a_successful_run(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'input.jtl'
            path.write_text('timeStamp,elapsed,responseCode,responseMessage,success\n')
            with self.assertRaises(ValueError):
                summarize(path, 1)

    def test_even_run_count_median_and_weighted_error_rate(self):
        runs = [dict(samples=10, errors=1, duration_ms=1000, timeouts=0,
                     p50_ms=10, p95_ms=20, p99_ms=30),
                dict(samples=90, errors=0, duration_ms=9000, timeouts=0,
                     p50_ms=20, p95_ms=30, p99_ms=40)]
        result = aggregate(runs)['aggregate']
        self.assertEqual(result['p99_ms'], 35)
        self.assertEqual(result['error_rate_percent'], 1)


if __name__ == '__main__':
    unittest.main()
