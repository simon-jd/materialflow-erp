"""kubectl access to a MaterialFlow namespace deployed with the chart's `faultInjection` switch.

Opt-in guard: the kube context must be named explicitly, and every erp-core and event-relay pod
must carry `materialflow.io/fault-injection=enabled` and run with `shareProcessNamespace: true`.
The application chart sets both only with `faultInjection.enabled=true`
(deploy/helm/materialflow/templates/deployments.yaml), so the drill refuses an ordinary release.

SQL runs in the psql toolbox pod (`app=materialflow-toolbox`, admin connection over TLS) and the
Kafka CLI in the broker pod, with a small heap so the tool JVM cannot push the broker container
over its memory limit. With two replicas the parked JVM is located rather than guessed: for
erp-core it is the consumer-group member that owns the domain topic's only partition (the
group's HOST column is the pod IP), for event-relay the pod named in the outbox row's
`claimed_by` (the relay's instance ID is its HOSTNAME, i.e. the pod name).

The JVM gets SIGKILL through `kubectl exec ... kill -KILL <pid>`. In a shared PID namespace it is
not PID 1, so the kernel delivers the signal, the container exits 137 and the kubelet restarts it
in place (same pod, same IP, restartCount + 1). A status poller started before the kill
timestamps the container's state, so each run records kill -> container running -> container
Ready separately from kill -> invariant restored. The kubelet's crash back-off (10 s doubling to
300 s unless the container ran longer than the current back-off) shows up in the first two.
"""
import json
import subprocess
import threading
import time

from .stack import (APP_SERVICES, CONSUMER_GROUP, DOMAIN_TOPIC, Stack, eventually, parse_group_owner,
                    parse_prometheus_counter, run)

FAULT_LABEL = 'materialflow.io/fault-injection'
KAFKA_POD, KAFKA_CONTAINER = 'kafka-0', 'kafka'
TOOLBOX_SELECTOR, TOOLBOX_CONTAINER = 'app=materialflow-toolbox', 'psql'
# The broker container runs with -Xms512m -Xmx512m and a 1 GiB limit; CLI tools inherit
# KAFKA_HEAP_OPTS, so they get their own small heap.
KAFKA_CLI_HEAP = 'KAFKA_HEAP_OPTS=-Xms32m -Xmx256m'
STATUS_FIELDS = ('name', 'restart_count', 'ready', 'running_started_at', 'waiting_reason', 'last_exit_code',
                 'last_reason', 'last_finished_at', 'container_id')
STATUS_TEMPLATE = ('{range .status.containerStatuses[*]}{.name}|{.restartCount}|{.ready}|{.state.running.startedAt}|'
                   '{.state.waiting.reason}|{.lastState.terminated.exitCode}|{.lastState.terminated.reason}|'
                   '{.lastState.terminated.finishedAt}|{.containerID}{"\\n"}{end}')


def parse_status_line(line):
    """One container status line printed with STATUS_TEMPLATE, or None for anything else."""
    values = line.rstrip('\n').split('|')
    if len(values) != len(STATUS_FIELDS) or not values[1].isdigit():
        return None
    status = dict(zip(STATUS_FIELDS, (value or None for value in values)))
    status['restart_count'] = int(values[1])
    status['ready'] = values[2] == 'true'
    status['last_exit_code'] = int(values[5]) if values[5].lstrip('-').isdigit() else None
    return status


def container_status(pod, container):
    """The same fields as parse_status_line, from a pod object."""
    for item in pod.get('status', {}).get('containerStatuses', []):
        if item['name'] == container:
            state, last = item.get('state', {}), item.get('lastState', {}).get('terminated', {})
            return dict(name=container, restart_count=item.get('restartCount', 0), ready=item.get('ready', False),
                        running_started_at=state.get('running', {}).get('startedAt'),
                        waiting_reason=state.get('waiting', {}).get('reason'),
                        last_exit_code=last.get('exitCode'), last_reason=last.get('reason'),
                        last_finished_at=last.get('finishedAt'), container_id=item.get('containerID'))
    raise AssertionError(f"{pod['metadata']['name']} has no container {container}")


def restart_timeline(events, killed, before):
    """Milliseconds from the kill to the first observation of the restarted container running and
    Ready, the waiting reasons seen in between (CrashLoopBackOff = kubelet back-off) and the last
    status. `events` are (monotonic time, status) from the pod watch."""
    after = [(at, status) for at, status in events if status['restart_count'] > before['restart_count']]
    running = next((at for at, status in after if status['running_started_at']), None)
    ready = next((at for at, status in after if status['ready']), None)
    waiting = sorted({status['waiting_reason'] for at, status in events if at >= killed and status['waiting_reason']})
    return dict(container_running_ms=None if running is None else round((running - killed) * 1000),
                container_ready_ms=None if ready is None else round((ready - killed) * 1000),
                waiting_reasons=waiting), (after[-1][1] if after else None)


class PodWatch:
    """Polls one pod's container status in the background and timestamps every reading when its
    `kubectl get` returns (about 0.4 s per call against AKS, which is the timing resolution).
    `kubectl get pod NAME --watch` was tried first: it exited as soon as the pod changed."""

    def __init__(self, kubectl, pod, container):
        self.args = [*kubectl, 'get', 'pod', pod, '-o', 'jsonpath=' + STATUS_TEMPLATE]
        self.container, self.events, self.errors = container, [], 0
        self.lock, self.stopped = threading.Lock(), threading.Event()
        self.thread = threading.Thread(target=self._poll, daemon=True)
        self.thread.start()

    def _poll(self):
        while not self.stopped.is_set():
            try:
                output = run(self.args, timeout=15)
            except (RuntimeError, subprocess.TimeoutExpired):
                self.errors += 1
                time.sleep(.5)
                continue
            at = time.monotonic()
            for status in (parse_status_line(line) for line in output.splitlines()):
                if status and status['name'] == self.container:
                    with self.lock:
                        self.events.append((at, status))

    def snapshot(self):
        with self.lock:
            return list(self.events)

    def wait(self, predicate, description, timeout):
        return eventually(lambda: next((at for at, status in self.snapshot() if predicate(status)), None),
                          description, timeout, interval=.1)

    def stop(self):
        self.stopped.set()
        self.thread.join(timeout=20)


class KubernetesStack(Stack):
    backend = 'kubernetes'

    def __init__(self, context, namespace='materialflow', token_file=None, ready_timeout=420):
        if not context:
            raise ValueError('Name the kube context explicitly (--kube-context); the current one is never assumed')
        self.context, self.namespace, self.token_file, self.ready_timeout = context, namespace, token_file, ready_timeout
        self.kubectl = ['kubectl', '--context', context, '-n', namespace]
        self.watches = {}
        for service in APP_SERVICES:
            pods = self.pods(service)
            assert pods, f'{namespace} has no running {service} pods'
            for pod in pods:
                name = pod['metadata']['name']
                assert pod['metadata'].get('labels', {}).get(FAULT_LABEL) == 'enabled' and \
                    pod['spec'].get('shareProcessNamespace'), \
                    f'{name} was not deployed with faultInjection.enabled=true; refusing to kill it'
        toolbox = self.get_json('get', 'pods', '-l', TOOLBOX_SELECTOR)['items']
        toolbox = [p['metadata']['name'] for p in toolbox if container_status(p, TOOLBOX_CONTAINER)['ready']]
        assert toolbox, f'{namespace} has no Ready psql toolbox pod'
        self.toolbox = toolbox[0]
        assert container_status(self.get_json('get', 'pod', KAFKA_POD), KAFKA_CONTAINER)['ready'], 'Kafka not Ready'

    def get_json(self, *args):
        return json.loads(run([*self.kubectl, *args, '-o', 'json']))

    def pods(self, service):
        """Pods of one app that are not being deleted, oldest first."""
        items = self.get_json('get', 'pods', '-l', 'app.kubernetes.io/name=materialflow-' + service)['items']
        return [p for p in items if not p['metadata'].get('deletionTimestamp')]

    def exec(self, pod, container, *command, text=None, timeout=30):
        return run([*self.kubectl, 'exec', *(['-i'] if text is not None else []), pod, '-c', container, '--',
                    *command], text, timeout)

    def psql(self, *args, app_name=None):
        env = ['env', 'PGAPPNAME=' + app_name] if app_name else []
        return [*self.kubectl, 'exec', self.toolbox, '-c', TOOLBOX_CONTAINER, '--', *env, 'psql', *args]

    def kafka_command(self, executable, interactive):
        return [*self.kubectl, 'exec', *(['-i'] if interactive else []), KAFKA_POD, '-c', KAFKA_CONTAINER, '--',
                'env', KAFKA_CLI_HEAP, '/opt/kafka/bin/' + executable]

    def images(self):
        return {service: [dict(pod=p['metadata']['name'], node=p['spec'].get('nodeName'),
                               image=next(c['image'] for c in p['status']['containerStatuses'] if c['name'] == service),
                               image_id=next(c['imageID'] for c in p['status']['containerStatuses']
                                             if c['name'] == service))
                          for p in self.pods(service)]
                for service in APP_SERVICES}

    def profile_active(self, service, profile):
        def profiles(pod):
            container = next(c for c in pod['spec']['containers'] if c['name'] == service)
            env = {e['name']: e.get('value', '') for e in container.get('env', [])}
            return env.get('SPRING_PROFILES_ACTIVE', '').split(',')
        pods = self.pods(service)
        return bool(pods) and all(profile in profiles(pod) for pod in pods)

    def running(self, service):
        pods = self.pods(service)
        return bool(pods) and all(container_status(p, service)['running_started_at'] for p in pods)

    def parked_pod(self, service, event_id):
        """The pod whose JVM is parked in the crash window."""
        if service == 'event-relay':
            name = self.sql(f"SELECT claimed_by FROM outbox_events WHERE id = '{event_id}' AND status = 'CLAIMED'")
            assert name in {p['metadata']['name'] for p in self.pods(service)}, f'claimed_by={name!r}'
            return name
        described = self.kafka('kafka-consumer-groups.sh', '--describe', '--group', CONSUMER_GROUP)
        owner = parse_group_owner(described, DOMAIN_TOPIC)
        pods = [p['metadata']['name'] for p in self.pods(service) if p['status'].get('podIP') == owner]
        assert len(pods) == 1, f'partition owner {owner} is not exactly one {service} pod: {pods}'
        return pods[0]

    def kill(self, service, event_id=None):
        pod_name = self.parked_pod(service, event_id)
        pod = self.get_json('get', 'pod', pod_name)
        before = container_status(pod, service)
        assert before['running_started_at'], before
        pids = self.exec(pod_name, service, 'pidof', 'java').split()
        assert len(pids) == 1 and pids[0] != '1', f'expected one java process that is not PID 1: {pids}'
        watch = PodWatch(self.kubectl, pod_name, service)
        watch.wait(lambda status: True, f'status poll of {pod_name} started', timeout=20)
        try:
            self.exec(pod_name, service, 'kill', '-KILL', pids[0], timeout=20)
            kill_exec = 'returned 0'
        except RuntimeError as failure:  # The exec session can die with the container it ran in.
            kill_exec = str(failure)[-200:]
        except subprocess.TimeoutExpired:
            kill_exec = 'timed out'
        watch.killed = time.monotonic()
        self.watches[pod_name] = (watch, service, before)
        return dict(pod=pod_name, node=pod['spec'].get('nodeName'), pod_ip=pod['status'].get('podIP'),
                    container_id_before=before['container_id'], restart_count_before=before['restart_count'],
                    started_before=before['running_started_at'], pid_before=int(pids[0]), kill_exec=kill_exec)

    def restart(self, service, process, killed):
        """Nothing to do: the kubelet restarts the container. Timing is recorded by `settle`."""
        process['restarted_by'] = 'kubelet'
        return None

    def settle(self, record):
        """Wait until the killed container is running and Ready again (and, for erp-core, the
        consumer group is Stable with every replica), then record the restart timeline and verify
        that it really was a SIGKILL restart in place."""
        process = record.get('process') or {}
        if process.get('pod') not in self.watches:
            return
        watch, service, before = self.watches.pop(process['pod'])
        try:
            restarted = lambda status: status['restart_count'] > before['restart_count']
            watch.wait(lambda s: restarted(s) and s['ready'], f"{process['pod']} Ready after the kill",
                       self.ready_timeout)
        finally:
            watch.stop()
            timeline, last = restart_timeline(watch.snapshot(), watch.killed, before)
            process.update(timeline, status_polls=len(watch.snapshot()), status_poll_errors=watch.errors)
            if last:
                process.update(exit_code=last['last_exit_code'], terminated_reason=last['last_reason'],
                               oom_killed=last['last_reason'] == 'OOMKilled', finished_at=last['last_finished_at'],
                               started_after=last['running_started_at'], restart_count_after=last['restart_count'],
                               container_id_after=last['container_id'])
        process['pid_after'] = int(self.exec(process['pod'], service, 'pidof', 'java'))
        record['restart_ms'] = process['container_running_ms']
        if record.get('recovery_ms') is not None and record['restart_ms'] is not None:
            record['restart_to_recovered_ms'] = record['recovery_ms'] - record['restart_ms']
        owner = record.get('recovered_owner_host')
        if owner:
            record['recovered_by'] = 'restarted pod' if owner == process['pod_ip'] else 'other replica'
        assert process['exit_code'] == 137 and not process['oom_killed'], process
        assert process['started_after'] != process['started_before'], process
        assert process['container_id_after'] != process['container_id_before'], process
        if service == 'erp-core':
            replicas = len(self.pods(service))
            eventually(lambda: self.group_state() == ('Stable', replicas) or None,
                       f'consumer group Stable with {replicas} members', timeout=180, interval=2)

    def erp_core_counters(self, name):
        """{(pod, JVM start): value} of one erp-core counter over all replicas, or None without a
        token. The token is passed on stdin, never on a command line."""
        if not self.token_file:
            return None
        with open(self.token_file, encoding='utf-8') as source:
            token = source.read().strip()
        values = {}
        for pod in self.pods('erp-core'):
            pod_name = pod['metadata']['name']
            try:
                exposition = self.exec(pod_name, 'erp-core', 'sh', '-c', 'read -r token; wget -qO- --header '
                                       '"Authorization: Bearer $token" http://localhost:8080/actuator/prometheus',
                                       text=token + '\n')
            except (RuntimeError, subprocess.TimeoutExpired):
                continue  # A JVM that is still starting has not counted anything yet.
            values[(pod_name, container_status(pod, 'erp-core')['running_started_at'])] = \
                parse_prometheus_counter(exposition, name)
        if not values:
            raise RuntimeError('no erp-core metrics endpoint was readable')
        return values
