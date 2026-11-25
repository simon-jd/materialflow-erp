"""Run any subset of the four crash windows N times each and record every SIGKILL."""
import argparse
import json
import sys
from pathlib import Path

from .report import summarize
from .stack import ALLOWED_PROJECTS, APP_SERVICES, FAULT_INJECTION_PROFILE, ComposeStack, utc_now
from .windows import WINDOWS

SCOPES = dict(compose='local synthetic Docker, real JVM SIGKILL, restarted by the drill (docker start)',
              kubernetes='Kubernetes namespace with faultInjection.enabled, real JVM SIGKILL, '
                         'container restarted in place by the kubelet')


def parse_windows(value):
    """'all', or a comma-separated list of window numbers and/or names, in matrix order."""
    if value == 'all':
        return list(WINDOWS)
    known = {w.name: w for w in WINDOWS} | {str(w.number): w for w in WINDOWS}
    chosen = set()
    for token in (t.strip() for t in value.split(',')):
        if token not in known:
            raise argparse.ArgumentTypeError(
                f"unknown window '{token}'; use all, 1-4 or {', '.join(w.name for w in WINDOWS)}")
        chosen.add(known[token])
    return [w for w in WINDOWS if w in chosen]


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('must be at least 1')
    return number


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog='process-kill-recovery.py', formatter_class=argparse.RawDescriptionHelpFormatter,
        description='Real SIGKILL fault matrix for the outbox -> Kafka -> consumer-inbox path. Compose: only '
                    'the disposable local qualification projects are accepted. Kubernetes: only a namespace '
                    'whose erp-core and event-relay pods were deployed with faultInjection.enabled=true, '
                    'through an explicitly named kube context.',
        epilog='windows:\n' + '\n'.join(f'  {w.number} {w.name:<21} kill {w.service}: {w.crash_point}'
                                        for w in WINDOWS))
    parser.add_argument('--backend', choices=('compose', 'kubernetes'), default='compose')
    parser.add_argument('--project', choices=ALLOWED_PROJECTS, default='materialflow-qualification-final',
                        help='Compose project (compose backend)')
    parser.add_argument('--kube-context', help='kube context to use; required for the kubernetes backend')
    parser.add_argument('--namespace', default='materialflow', help='application namespace (kubernetes backend)')
    parser.add_argument('--token-file', type=Path,
                        help='file holding a bearer token for erp-core /actuator/prometheus (kubernetes backend, '
                             'optional: without it the duplicate counter of window 2 is recorded as null)')
    parser.add_argument('--windows', type=parse_windows, default='all',
                        help='all (default), or comma-separated numbers/names, e.g. 1,3 or dlq-post-ack')
    parser.add_argument('--repeat', type=positive_int, default=1, help='kills per window (default 1)')
    parser.add_argument('--output-dir', type=Path, required=True,
                        help='new directory for kills.jsonl (one record per run) and summary.json')
    args = parser.parse_args(argv)
    if args.backend == 'kubernetes' and not args.kube_context:
        parser.error('--backend kubernetes requires --kube-context')
    return args


def open_stack(args):
    if args.backend == 'kubernetes':
        from .kubernetes import KubernetesStack
        return KubernetesStack(args.kube_context, args.namespace,
                               token_file=str(args.token_file) if args.token_file else None)
    return ComposeStack(args.project)


def run_window(stack, window, iteration):
    record = dict(window=window.name, window_number=window.number, iteration=iteration, service=window.service,
                  crash_point=window.crash_point, expected=window.expected, started_at=utc_now())
    try:
        window.run(stack, record)
        record['status'] = 'passed'
    except Exception as failure:  # Recorded, then the matrix goes on if the stack is intact.
        record.update(status='failed', failed_assertion=f'{type(failure).__name__}: {failure}')
    try:
        stack.settle(record)  # Kubernetes: wait for the in-place restart and record its timeline.
    except Exception as failure:
        if record['status'] == 'passed':
            record.update(status='failed', failed_assertion=f'settle: {type(failure).__name__}: {failure}')
        else:
            record['settle_error'] = f'{type(failure).__name__}: {failure}'
    record['finished_at'] = utc_now()
    return record


def run_matrix(stack, windows, repeat, sink, records):
    """Round-robin over the windows so each window's runs spread across the session. Appends
    to `records` as it goes, so an aborted matrix still summarizes the runs it finished."""
    total = len(windows) * repeat
    for iteration in range(1, repeat + 1):
        for window in windows:
            record = run_window(stack, window, iteration)
            records.append(record)
            sink.write(json.dumps(record) + '\n')
            sink.flush()
            process = record.get('process') or {}
            print(f"[{len(records)}/{total}] {window.number} {window.name} #{iteration}: {record['status']}"
                  f" recovery_ms={record.get('recovery_ms')} running_ms={process.get('container_running_ms')}"
                  f" ready_ms={process.get('container_ready_ms')} {process.get('pod', '')}"
                  f" {record.get('failed_assertion', '')}", file=sys.stderr, flush=True)
            stopped = [s for s in APP_SERVICES if not stack.running(s)]
            if stopped:
                raise RuntimeError(f'{stopped} not running after {window.name} #{iteration}; matrix aborted')


def main(argv=None):
    args = parse_args(argv)
    args.output_dir.mkdir(parents=True, exist_ok=False)  # Reserve evidence before touching containers.
    target = dict(project=args.project) if args.backend == 'compose' else dict(
        kube_context=args.kube_context, namespace=args.namespace)
    summary = dict(backend=args.backend, **target, scope=SCOPES[args.backend], started_at=utc_now(),
                   windows=[w.name for w in args.windows], repeat=args.repeat)
    records = []
    try:
        stack = open_stack(args)
        summary['images'] = stack.images()
        gated = [w.name for w in args.windows if w.needs_fault_injection]
        if gated and not stack.profile_active('erp-core', FAULT_INJECTION_PROFILE):
            raise SystemExit(f'{gated} need erp-core started with SPRING_PROFILES_ACTIVE='
                             f'{FAULT_INJECTION_PROFILE} (deploy/compose/fault-injection.yml, or the chart\'s '
                             'faultInjection.enabled=true)')
        with (args.output_dir / 'kills.jsonl').open('x') as sink:
            run_matrix(stack, args.windows, args.repeat, sink, records)
    except BaseException as failure:
        summary['aborted'] = f'{type(failure).__name__}: {failure}'
        raise
    finally:
        summary.update(summarize(records, args.windows), finished_at=utc_now())
        if 'aborted' in summary:
            summary['status'] = 'failed'
        (args.output_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    return 0 if summary['status'] == 'passed' else 1
