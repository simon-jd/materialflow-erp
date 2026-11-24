import tempfile
import unittest
from pathlib import Path

from summarize_hot_sku import summarize_hot_sku


class HotSkuSummaryTest(unittest.TestCase):
    def test_burst_throughput_excludes_pauses_between_waves(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'input.jtl'
            path.write_text('timeStamp,elapsed,label,responseCode,responseMessage,success\n'
                            '1000,100,POST order wave 0,201,Created,true\n'
                            '1050,150,POST order wave 0,201,Created,true\n'
                            '4800,200,POST order wave 1,201,Created,true\n'
                            '4800,100,POST order wave 1,429,Too Many Requests,false\n')
            result = summarize_hot_sku(path, 1)
        self.assertEqual([wave['span_ms'] for wave in result['waves']], [200, 200])
        self.assertEqual(result['burst_throughput_rps'], 10)
        self.assertEqual(result['throughput_rps'], 1)
        self.assertEqual(result['status_codes'], {'201': 3, '429': 1})
        self.assertEqual(result['errors'], 1)


if __name__ == '__main__':
    unittest.main()
