"""Docker-free tests for the fault-matrix drill: arguments, parsing, records and aggregation."""
import contextlib
import io
import json
import unittest
from pathlib import Path

from fault_matrix import cli, report, stack, windows

KILLED = dict(exit_code=137)


def record(window, iteration, status='passed', recovery_ms=None, restart_ms=500, process=KILLED, **extra):
    value = dict(window=window, iteration=iteration, status=status, process=process, **extra)
    if status == 'passed':
        value.update(recovery_ms=recovery_ms, restart_to_recovered_ms=recovery_ms - restart_ms)
    return value


class ArgumentsTest(unittest.TestCase):
    def parse(self, *argv):
        return cli.parse_args(['--output-dir', 'out', *argv])

    def rejects(self, *argv):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.parse(*argv)

    def test_defaults_run_every_window_once_against_the_final_qualification_project(self):
        args = self.parse()
        self.assertEqual([w.number for w in args.windows], [1, 2, 3, 4])
        self.assertEqual((args.repeat, args.project, args.output_dir),
                         (1, 'materialflow-qualification-final', Path('out')))

    def test_numbers_and_names_mix_deduplicate_and_keep_matrix_order(self):
        args = self.parse('--windows', 'dlq-post-ack, 1,4', '--repeat', '5', '--project', 'mf-fault-matrix')
        self.assertEqual([w.name for w in args.windows], ['consumer-pre-commit', 'dlq-post-ack'])
        self.assertEqual(args.repeat, 5)

    def test_unknown_window_zero_repeat_foreign_project_and_missing_output_are_rejected(self):
        self.rejects('--windows', '5')
        self.rejects('--windows', 'consumer')
        self.rejects('--repeat', '0')
        self.rejects('--project', 'materialflow')
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            cli.parse_args([])

    def test_only_the_offset_gated_windows_need_the_fault_injection_profile(self):
        self.assertEqual([w.number for w in windows.WINDOWS if w.needs_fault_injection], [2, 4])


class SummaryTest(unittest.TestCase):
    def test_per_window_counts_and_nearest_rank_recovery_over_passed_runs(self):
        first, third = windows.WINDOWS[0], windows.WINDOWS[2]
        records = [record(first.name, i, recovery_ms=ms) for i, ms in enumerate([900, 700, 1100, 800], 1)]
        records += [record(first.name, 5, status='failed', failed_assertion='AssertionError: inbox_rows=2')]
        records += [record(third.name, 1, recovery_ms=31000),
                    record(third.name, 2, status='failed', process={}, failed_assertion='AssertionError: Timed out')]
        summary = report.summarize(records, [first, third])
        consumer = summary['per_window'][first.name]
        counts = tuple(consumer[key] for key in ('runs', 'sigkills', 'passed', 'failed'))
        self.assertEqual(counts, (5, 5, 4, 1))
        self.assertEqual(consumer['recovery_ms'], dict(p50=800, max=1100, min=700))
        self.assertEqual(consumer['restart_to_recovered_ms']['p50'], 300)
        self.assertEqual(consumer['failures'], [dict(iteration=5, failed_assertion='AssertionError: inbox_rows=2')])
        self.assertEqual(summary['per_window'][third.name]['sigkills'], 1)  # Failed before its kill.
        self.assertEqual(summary['totals'], dict(runs=7, sigkills=6, passed=5, failed=2))
        self.assertEqual(summary['status'], 'failed')

    def test_all_passed_is_passed_and_no_runs_is_not(self):
        window = windows.WINDOWS[1]
        self.assertEqual(report.summarize([record(window.name, 1, recovery_ms=10)], [window])['status'], 'passed')
        empty = report.summarize([], [window])
        self.assertEqual(empty['status'], 'failed')
        self.assertEqual(empty['per_window'][window.name]['recovery_ms'], dict(p50=None, max=None, min=None))

    def test_nearest_rank(self):
        self.assertEqual(report.nearest_rank([4, 1, 3, 2], 50), 2)
        self.assertEqual(report.nearest_rank([5], 50), 5)
        self.assertEqual(report.nearest_rank([1, 2, 3, 4, 5], 95), 5)


class ParsingTest(unittest.TestCase):
    def test_consumer_group_offsets_with_and_without_a_commit(self):
        described = ('\nGROUP                    TOPIC                         PARTITION  CURRENT-OFFSET  '
                     'LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID\n'
                     'erp-core-reallocation-v1 materialflow.domain-events.v1 0  41  43  2  -  -  -\n')
        self.assertEqual(stack.parse_group_offsets(described, stack.DOMAIN_TOPIC), (41, 43))
        self.assertEqual(stack.parse_group_offsets(described.replace(' 41 ', ' - '), stack.DOMAIN_TOPIC),
                         (None, 43))
        with self.assertRaises(AssertionError):
            stack.parse_group_offsets("Consumer group 'g' does not exist.", stack.DOMAIN_TOPIC)

    def test_console_records_keep_tabs_and_pipes_inside_values(self):
        output = 'Offset:7\tkey-a\t{"a":"x\\ty"}\nOffset:8\tkey-b\tplain|value'
        self.assertEqual(stack.parse_console_records(output),
                         [(7, 'key-a', '{"a":"x\\ty"}'), (8, 'key-b', 'plain|value')])

    def test_prometheus_counter_sums_series_and_ignores_comments(self):
        exposition = ('# HELP event_duplicate_total\n# TYPE event_duplicate_total counter\n'
                      'event_duplicate_total{consumer="a"} 1.0\nevent_duplicate_total{consumer="b"} 2.0\n'
                      'event_duplicate_total_other 9.0\n')
        self.assertEqual(stack.parse_prometheus_counter(exposition, 'event_duplicate_total'), 3.0)
        self.assertEqual(stack.parse_prometheus_counter('', 'event_duplicate_total'), 0.0)

    def test_advisory_lock_key_splits_into_pg_locks_ids(self):
        self.assertEqual(stack.advisory_lock_ids((7 << 32) | 9), (7, 9))

    def test_foreign_projects_are_refused_before_any_docker_call(self):
        with self.assertRaises(ValueError):
            stack.Stack('materialflow')


class EventuallyTest(unittest.TestCase):
    def test_zero_is_a_result_and_none_means_not_yet(self):
        values = iter([None, 0])
        self.assertEqual(stack.eventually(lambda: next(values), 'offset', timeout=1, interval=0), 0)

    def test_timeout_reports_the_last_probe_error(self):
        def probe():
            raise RuntimeError('group is rebalancing')
        with self.assertRaisesRegex(AssertionError, 'Timed out: offset .*group is rebalancing'):
            stack.eventually(probe, 'offset', timeout=0, interval=0)


class WindowLogicTest(unittest.TestCase):
    def test_business_effect_must_be_consistent_across_ledger_balance_line_order_and_outbox(self):
        once = dict(business_updates=1, follow_up_events=1, reserved_quantity=10.0, allocated_quantity=10.0,
                    order_status='RESERVED')
        none = dict(business_updates=0, follow_up_events=0, reserved_quantity=0.0, allocated_quantity=0.0,
                    order_status='BACKORDERED')
        self.assertTrue(windows.business_applied(once, 1))
        self.assertTrue(windows.business_applied(none, 0))
        self.assertFalse(windows.business_applied(once, 0))
        self.assertFalse(windows.business_applied(dict(once, reserved_quantity=20.0), 1))
        self.assertFalse(windows.business_applied(dict(once, follow_up_events=2), 1))

    def test_invalid_envelope_omits_the_organization(self):
        value = json.loads(windows.envelope('e', 'InventoryReceived', 'a', organization_id=None))
        self.assertNotIn('organizationId', value)
        self.assertEqual(json.loads(windows.envelope('e', 'X', 'a'))['organizationId'], windows.ORGANIZATION)

    def test_gate_headers_match_the_java_consumer_crash_window_gate(self):
        self.assertEqual(windows.AFTER_INBOX_COMMIT_HEADER, 'materialflow-crash-gate-after-inbox-commit')
        self.assertEqual(windows.AFTER_DEAD_LETTER_SEND_HEADER, 'materialflow-crash-gate-after-dead-letter-send')


class FakeStack:
    def __init__(self, stop_after=None):
        self.runs, self.stop_after = [], stop_after

    def running(self, service):
        return self.stop_after is None or len(self.runs) < self.stop_after


def fake_window(number, outcome):
    def run(fake, rec):
        fake.runs.append((number, rec['iteration']))
        rec.update(process=KILLED, recovery_ms=number * 100, restart_to_recovered_ms=number * 10)
        if outcome:
            raise outcome
    return windows.Window(number, f'w{number}', 'erp-core', 'point', 'expected', run)


class MatrixTest(unittest.TestCase):
    def test_round_robin_records_failures_and_keeps_going(self):
        fake, sink, records = FakeStack(), io.StringIO(), []
        matrix = [fake_window(1, None), fake_window(2, AssertionError('inbox_rows=2'))]
        with contextlib.redirect_stderr(io.StringIO()):
            cli.run_matrix(fake, matrix, 2, sink, records)
        self.assertEqual(fake.runs, [(1, 1), (2, 1), (1, 2), (2, 2)])
        self.assertEqual([r['status'] for r in records], ['passed', 'failed'] * 2)
        self.assertEqual(records[1]['failed_assertion'], 'AssertionError: inbox_rows=2')
        self.assertEqual([json.loads(line)['window'] for line in sink.getvalue().splitlines()],
                         ['w1', 'w2', 'w1', 'w2'])

    def test_a_service_left_down_aborts_but_keeps_finished_records(self):
        fake, records = FakeStack(stop_after=1), []
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(RuntimeError):
            cli.run_matrix(fake, [fake_window(1, None)], 3, io.StringIO(), records)
        self.assertEqual(len(records), 1)


if __name__ == '__main__':
    unittest.main()
