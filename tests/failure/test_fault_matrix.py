"""Docker- and cluster-free tests for the fault-matrix drill: arguments, parsing, records and aggregation."""
import contextlib
import io
import json
import time
import unittest
import unittest.mock
from pathlib import Path

from fault_matrix import cli, kubernetes, report, stack, windows

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

    def test_kubernetes_backend_needs_an_explicit_context(self):
        self.rejects('--backend', 'kubernetes')
        args = self.parse('--backend', 'kubernetes', '--kube-context', 'aks-materialflow-dev', '--token-file', 't')
        self.assertEqual((args.backend, args.kube_context, args.namespace, args.token_file),
                         ('kubernetes', 'aks-materialflow-dev', 'materialflow', Path('t')))
        self.assertEqual(self.parse().backend, 'compose')

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

    def test_kubernetes_restart_timeline_is_summarized_and_absent_values_are_skipped(self):
        window = windows.WINDOWS[0]
        records = [record(window.name, 1, recovery_ms=12000, restart_ms=3000,
                          process=dict(exit_code=137, container_running_ms=3000, container_ready_ms=25000)),
                   record(window.name, 2, recovery_ms=11000, restart_ms=13000,
                          process=dict(exit_code=137, container_running_ms=13000, container_ready_ms=40000)),
                   dict(window=window.name, iteration=3, status='passed', recovery_ms=14000, process=KILLED)]
        summary = report.summarize(records, [window])['per_window'][window.name]
        self.assertEqual(summary['container_running_ms'], dict(p50=3000, max=13000, min=3000))
        self.assertEqual(summary['container_ready_ms']['max'], 40000)
        self.assertEqual(summary['restart_to_recovered_ms'], dict(p50=-2000, max=9000, min=-2000))
        self.assertEqual(summary['recovery_ms']['p50'], 12000)

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

    def test_partition_owner_host_and_group_state(self):
        described = ('GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n'
                     'erp-core-reallocation-v1 materialflow.domain-events.v1 0 12 12 0 '
                     'consumer-erp-core-reallocation-v1-1-2d24 /10.244.1.76 consumer-erp-core-reallocation-v1-1\n')
        self.assertEqual(stack.parse_group_owner(described, stack.DOMAIN_TOPIC), '10.244.1.76')
        rebalancing = ('erp-core-reallocation-v1 materialflow.domain-events.v1 0 12 13 1 - - -\n')
        self.assertIsNone(stack.parse_group_owner(rebalancing, stack.DOMAIN_TOPIC))
        state = ('\nGROUP                    COORDINATOR (ID)          ASSIGNMENT-STRATEGY  STATE   #MEMBERS\n'
                 'erp-core-reallocation-v1 kafka:9092  (1)           range                Stable  2\n')
        self.assertEqual(stack.parse_group_state(state), ('Stable', 2))
        with self.assertRaises(AssertionError):
            stack.parse_group_state("Consumer group 'erp-core-reallocation-v1' does not exist.")

    def test_counter_increase_counts_a_restarted_jvm_from_zero(self):
        before = {('pod-a', 't1'): 4.0, ('pod-b', 't1'): 2.0}
        self.assertEqual(stack.counter_increase(before, {('pod-a', 't2'): 1.0, ('pod-b', 't1'): 2.0}), 1.0)
        self.assertEqual(stack.counter_increase(before, {('pod-a', 't2'): 0.0, ('pod-b', 't1'): 3.0}), 1.0)
        self.assertEqual(stack.counter_increase({('c', 's1'): 7.0}, {('c', 's2'): 1.0}), 1.0)  # Compose
        self.assertIsNone(stack.counter_increase(None, before))
        self.assertIsNone(stack.counter_increase(before, None))

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
            stack.ComposeStack('materialflow')

    def test_kubernetes_stack_refuses_an_implicit_context_before_any_kubectl_call(self):
        with self.assertRaises(ValueError):
            kubernetes.KubernetesStack('')


class KubernetesStatusTest(unittest.TestCase):
    LINE = 'erp-core|{restarts}|{ready}|{started}|{waiting}|{code}|{reason}|2026-11-24T20:01:02Z|containerd://{cid}\n'

    def status(self, restarts=1, ready='false', started='', waiting='', code='137', reason='Error', cid='b'):
        return kubernetes.parse_status_line(self.LINE.format(restarts=restarts, ready=ready, started=started,
                                                             waiting=waiting, code=code, reason=reason, cid=cid))

    def test_status_line_fields(self):
        status = self.status(restarts=3, ready='true', started='2026-11-24T20:01:05Z')
        self.assertEqual((status['restart_count'], status['ready'], status['running_started_at'],
                          status['last_exit_code'], status['last_reason'], status['container_id']),
                         (3, True, '2026-11-24T20:01:05Z', 137, 'Error', 'containerd://b'))
        fresh = self.status(restarts=0, code='', reason='')
        self.assertEqual((fresh['last_exit_code'], fresh['last_reason'], fresh['waiting_reason']), (None, None, None))
        self.assertIsNone(kubernetes.parse_status_line('Error from server (NotFound): pods "x" not found\n'))

    def test_status_from_a_pod_object_matches_the_watch_line(self):
        pod = dict(metadata=dict(name='p'), status=dict(containerStatuses=[dict(
            name='erp-core', restartCount=1, ready=False, containerID='containerd://b',
            state=dict(waiting=dict(reason='CrashLoopBackOff')),
            lastState=dict(terminated=dict(exitCode=137, reason='Error', finishedAt='2026-11-24T20:01:02Z')))]))
        self.assertEqual(kubernetes.container_status(pod, 'erp-core'),
                         self.status(waiting='CrashLoopBackOff'))
        with self.assertRaises(AssertionError):
            kubernetes.container_status(pod, 'event-relay')

    def test_restart_timeline_separates_running_ready_and_back_off(self):
        before = self.status(restarts=0, ready='true', started='2026-11-24T20:00:00Z', code='', reason='')
        events = [(10.0, before),
                  (11.0, self.status(restarts=0, ready='false', code='', reason='', waiting='')),
                  (11.5, self.status(restarts=1, waiting='CrashLoopBackOff')),
                  (21.2, self.status(restarts=1, started='2026-11-24T20:01:12Z')),
                  (48.0, self.status(restarts=1, ready='true', started='2026-11-24T20:01:12Z'))]
        timeline, last = kubernetes.restart_timeline(events, killed=10.5, before=before)
        self.assertEqual(timeline, dict(container_running_ms=10700, container_ready_ms=37500,
                                        waiting_reasons=['CrashLoopBackOff']))
        self.assertTrue(last['ready'])
        nothing, none = kubernetes.restart_timeline(events[:2], killed=10.5, before=before)
        self.assertEqual((nothing['container_running_ms'], nothing['container_ready_ms'], none), (None, None, None))


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

    def test_absorbed_duplicates_waits_for_a_count_and_skips_stacks_without_metrics(self):
        class Metrics:
            def __init__(self, *values):
                self.values = iter(values)

            def erp_core_counters(self, name):
                value = next(self.values)
                if isinstance(value, Exception):
                    raise value
                return value
        before = {('pod-a', 't1'): 2.0, ('pod-b', 't1'): 0.0}
        metrics = Metrics(RuntimeError('starting'), {('pod-a', 't1'): 2.0}, {('pod-a', 't1'): 2.0, ('pod-b', 't1'): 1.0})
        with unittest.mock.patch('time.sleep'):
            self.assertEqual(windows.absorbed_duplicates(metrics, before), 1.0)
        self.assertIsNone(windows.absorbed_duplicates(Metrics(), None))

    def test_recovered_records_the_owner_and_splits_restart_from_recovery(self):
        class Owner:
            def __init__(self, host):
                self.host = host

            def last_group_owner(self):
                return self.host
        compose = dict(restart_ms=300)
        self.assertEqual(windows.recovered(Owner('10.244.0.9'), compose, time.monotonic(), lambda: 0, 'x'), 0)
        self.assertEqual(compose['recovered_owner_host'], '10.244.0.9')
        self.assertEqual(compose['restart_to_recovered_ms'], compose['recovery_ms'] - 300)
        kubelet = dict(restart_ms=None)
        windows.recovered(Owner(None), kubelet, time.monotonic(), lambda: 1, 'x')
        self.assertNotIn('restart_to_recovered_ms', kubelet)
        self.assertNotIn('recovered_owner_host', kubelet)

    def test_gate_headers_match_the_java_consumer_crash_window_gate(self):
        self.assertEqual(windows.AFTER_INBOX_COMMIT_HEADER, 'materialflow-crash-gate-after-inbox-commit')
        self.assertEqual(windows.AFTER_DEAD_LETTER_SEND_HEADER, 'materialflow-crash-gate-after-dead-letter-send')


class FakeStack:
    def __init__(self, stop_after=None, settle_failure=None):
        self.runs, self.stop_after, self.settle_failure, self.settled = [], stop_after, settle_failure, []

    def running(self, service):
        return self.stop_after is None or len(self.runs) < self.stop_after

    def settle(self, rec):
        self.settled.append(rec['iteration'])
        if self.settle_failure:
            raise self.settle_failure


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

    def test_every_run_is_settled_and_a_failed_settle_fails_a_passed_run(self):
        fake = FakeStack(settle_failure=AssertionError('exit_code 143'))
        records = []
        with contextlib.redirect_stderr(io.StringIO()):
            cli.run_matrix(fake, [fake_window(1, None), fake_window(2, AssertionError('x'))], 1, io.StringIO(), records)
        self.assertEqual(fake.settled, [1, 1])
        self.assertEqual(records[0]['failed_assertion'], 'settle: AssertionError: exit_code 143')
        self.assertEqual((records[1]['failed_assertion'], records[1]['settle_error']),
                         ('AssertionError: x', 'AssertionError: exit_code 143'))

    def test_a_service_left_down_aborts_but_keeps_finished_records(self):
        fake, records = FakeStack(stop_after=1), []
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(RuntimeError):
            cli.run_matrix(fake, [fake_window(1, None)], 3, io.StringIO(), records)
        self.assertEqual(len(records), 1)


if __name__ == '__main__':
    unittest.main()
