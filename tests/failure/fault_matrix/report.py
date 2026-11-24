"""Per-window aggregation of kill records. Pure functions; no Docker needed."""
import math


def nearest_rank(values, percentile):
    ordered = sorted(values)
    return ordered[max(math.ceil(len(ordered) * percentile / 100) - 1, 0)] if ordered else None


def distribution(values):
    return dict(p50=nearest_rank(values, 50), max=max(values, default=None), min=min(values, default=None))


def present(values):
    return [value for value in values if value is not None]


def summarize(records, windows):
    """Pass counts and recovery-time distribution per window, in matrix order.

    A run counts as a SIGKILL only when the container exited with 137; recovery times
    come from passed runs only, because a failed run has no restored invariant. On
    Kubernetes the restart is the kubelet's, so kill -> container running and kill ->
    container Ready are reported next to kill -> invariant restored (None on Compose).
    """
    per_window = {}
    for window in windows:
        runs = [r for r in records if r['window'] == window.name]
        passed = [r for r in runs if r['status'] == 'passed']
        per_window[window.name] = dict(
            number=window.number, service=window.service, runs=len(runs),
            sigkills=sum(r.get('process', {}).get('exit_code') == 137 for r in runs),
            passed=len(passed), failed=len(runs) - len(passed),
            recovery_ms=distribution([r['recovery_ms'] for r in passed]),
            restart_to_recovered_ms=distribution(present(r.get('restart_to_recovered_ms') for r in passed)),
            container_running_ms=distribution(present((r.get('process') or {}).get('container_running_ms') for r in passed)),
            container_ready_ms=distribution(present((r.get('process') or {}).get('container_ready_ms') for r in passed)),
            failures=[dict(iteration=r['iteration'], failed_assertion=r['failed_assertion'])
                      for r in runs if r['status'] != 'passed'])
    totals = {key: sum(w[key] for w in per_window.values()) for key in ('runs', 'sigkills', 'passed', 'failed')}
    return dict(status='passed' if totals['runs'] and not totals['failed'] else 'failed',
                totals=totals, per_window=per_window)
