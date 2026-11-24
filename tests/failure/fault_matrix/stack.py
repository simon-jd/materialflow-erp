"""PostgreSQL, Kafka and process access to one disposable MaterialFlow stack.

`Stack` holds everything the crash windows do through SQL and the Kafka CLI. A backend only
decides how a command reaches the right container and how a JVM is killed and comes back:

* `ComposeStack` (this module): `docker exec` into one disposable qualification Compose project;
  the drill restarts the killed container itself with `docker start`.
* `KubernetesStack` (kubernetes.py): `kubectl exec` into the psql toolbox and the Kafka broker pod
  of a namespace deployed with the chart's `faultInjection` switch; the kubelet restarts the
  killed container in place.

Fixture triggers and lock holders are named `mf_qualification_*`, are specific to one event and
are removed after each run.
"""
import datetime
import json
import subprocess
import time
import uuid
from dataclasses import dataclass

ALLOWED_PROJECTS = ('materialflow-qualification', 'materialflow-qualification-boot4',
                    'materialflow-qualification-final', 'mf-fault-matrix')
DOMAIN_TOPIC = 'materialflow.domain-events.v1'
DLQ_TOPIC = DOMAIN_TOPIC + '.DLQ'
CONSUMER_GROUP = 'erp-core-reallocation-v1'
FAULT_INJECTION_PROFILE = 'fault-injection'
APP_SERVICES = ('erp-core', 'event-relay')
LOCK_HOLD_SECONDS = 180  # Upper bound if the drill itself dies before releasing a gate.


def run(args, text=None, timeout=30):
    stdin = dict(input=text) if text is not None else dict(stdin=subprocess.DEVNULL)
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout, **stdin)
    if result.returncode:
        raise RuntimeError(f"{' '.join(args[:5])} exited {result.returncode}: {result.stderr.strip()[-600:]}")
    return result.stdout.strip()


def eventually(probe, description, timeout=90, interval=.25):
    """Poll until `probe` returns something other than None. Probe errors (for example a
    consumer-group query during a rebalance) count as not ready and are reported on timeout."""
    deadline, error = time.monotonic() + timeout, None
    while True:
        try:
            value = probe()
            if value is not None:
                return value
        except (AssertionError, RuntimeError, subprocess.TimeoutExpired) as failure:
            error = failure
        if time.monotonic() >= deadline:
            raise AssertionError(f'Timed out: {description}' + (f' (last probe error: {error})' if error else ''))
        time.sleep(interval)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def group_rows(describe_output, topic, partition=0):
    for row in (line.split() for line in describe_output.splitlines()):
        if len(row) > 4 and row[1] == topic and row[2] == str(partition) and row[4].isdigit():
            yield row


def parse_group_offsets(describe_output, topic, partition=0):
    """(committed offset or None, log-end offset) from `kafka-consumer-groups.sh --describe`."""
    for row in group_rows(describe_output, topic, partition):
        return (int(row[3]) if row[3].isdigit() else None), int(row[4])
    raise AssertionError(f'No {topic}-{partition} row in consumer-group description:\n{describe_output}')


def parse_group_owner(describe_output, topic, partition=0):
    """Client host (IP) of the member that owns the partition, or None while it is unassigned."""
    for row in group_rows(describe_output, topic, partition):
        host = row[7] if len(row) > 7 else '-'
        return host.lstrip('/') if host != '-' else None
    raise AssertionError(f'No {topic}-{partition} row in consumer-group description:\n{describe_output}')


def parse_group_state(state_output):
    """(state, member count) from `kafka-consumer-groups.sh --describe --state`."""
    for row in (line.split() for line in state_output.splitlines()):
        if len(row) >= 6 and row[0] == CONSUMER_GROUP and row[-1].isdigit():
            return row[-2], int(row[-1])
    raise AssertionError(f'No {CONSUMER_GROUP} row in group state:\n{state_output}')


def parse_console_records(output):
    """[(offset, key, value)] from kafka-console-consumer with print.offset and print.key."""
    records = []
    for line in output.splitlines():
        offset, key, value = line.split('\t', 2)
        records.append((int(offset.removeprefix('Offset:')), key, value))
    return records


def parse_prometheus_counter(exposition, name):
    """Sum of all series of one counter in Prometheus text format; 0 when absent."""
    total = 0.0
    for line in exposition.splitlines():
        if line.startswith(name + '{') or line.startswith(name + ' '):
            total += float(line.rsplit(' ', 1)[1])
    return total


def counter_increase(before, after):
    """Increase of a counter summed over JVMs. Both arguments map (instance, JVM start) to a value;
    a JVM absent from `before` (it was restarted in between) counts from zero. None if either side
    could not be read."""
    if before is None or after is None:
        return None
    return sum(value - before.get(jvm, 0.0) for jvm, value in after.items())


def advisory_lock_ids(key):
    """pg_locks (classid, objid) of a single bigint advisory-lock key."""
    return key >> 32, key & 0xFFFFFFFF


@dataclass
class Gate:
    """An advisory lock held by a psql session the drill owns; the application queues behind it."""
    name: str
    key: int
    holder: subprocess.Popen
    table: str | None = None


class Stack:
    """SQL, gates and Kafka shared by both backends. Subclasses provide the command prefixes
    (`psql`, `kafka_command`) and process control (`kill`, `restart`, `settle`, `running`,
    `images`, `profile_active`, `erp_core_counters`)."""

    backend = None

    def psql(self, *args, app_name=None):
        raise NotImplementedError

    def kafka_command(self, executable, interactive):
        raise NotImplementedError

    def settle(self, record):
        """Called after every run, passed or failed: wait until the killed process is back and add
        what the backend observed about the restart to the record. Nothing to do by default."""

    def last_group_owner(self):
        """Host of the domain partition's owner in the latest consumer-group description."""
        return getattr(self, '_last_group_owner', None)

    def forget_group_owner(self):
        self._last_group_owner = None

    # PostgreSQL

    def sql(self, statement):
        return run(self.psql('-XAt', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-c', statement))

    def scalar(self, statement):
        return int(self.sql(statement))

    def gate(self, trigger=None):
        """Hold a fresh advisory lock; with `trigger=(table, column, value, event)` also make the
        application's matching row write wait on it inside its own transaction."""
        name = 'mf_qualification_' + uuid.uuid4().hex
        key = uuid.uuid4().int % (2 ** 62)
        holder = subprocess.Popen(self.psql('-XAt', '-c', f'SELECT pg_advisory_lock({key}); '
                                            f'SELECT pg_sleep({LOCK_HOLD_SECONDS})', app_name=name),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        gate = Gate(name, key, holder)
        try:
            eventually(lambda: self.scalar(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{name}' "
                                           "AND wait_event='PgSleep'") == 1 or None, 'fixture owns advisory lock')
            if trigger:
                table, column, value, event = trigger
                self.sql(f"CREATE FUNCTION {name}() RETURNS trigger LANGUAGE plpgsql AS $$ "
                         f"BEGIN PERFORM pg_advisory_xact_lock({key}); RETURN NEW; END $$; "
                         f"CREATE TRIGGER {name} AFTER {event} ON {table} FOR EACH ROW "
                         f"WHEN (NEW.{column}='{value}'::uuid) EXECUTE FUNCTION {name}()")
                gate.table = table
        except Exception:
            self.release(gate)
            raise
        return gate

    def wait_blocked(self, gate, timeout=60):
        """Statement of the application session queued on the gate, once it is queued."""
        classid, objid = advisory_lock_ids(gate.key)
        return eventually(lambda: self.sql(
            "SELECT a.query FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
            f"WHERE l.locktype = 'advisory' AND NOT l.granted AND l.classid = '{classid}'::oid "
            f"AND l.objid = '{objid}'::oid AND l.objsubid = 1") or None, 'application blocked in crash window', timeout)

    def release(self, gate):
        self.sql(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{gate.name}'")
        gate.holder.communicate(timeout=10)
        if gate.table:
            self.sql(f'DROP TRIGGER IF EXISTS {gate.name} ON {gate.table}; DROP FUNCTION IF EXISTS {gate.name}()')

    # Kafka

    def kafka(self, executable, *args, text=None, timeout=45):
        return run([*self.kafka_command(executable, interactive=text is not None),
                    '--bootstrap-server', 'localhost:9092', *args], text, timeout)

    def create_topic(self, topic):
        self.kafka('kafka-topics.sh', '--create', '--topic', topic, '--partitions', '1', '--replication-factor', '1')

    def group_offsets(self, topic=DOMAIN_TOPIC, group=CONSUMER_GROUP):
        described = self.kafka('kafka-consumer-groups.sh', '--describe', '--group', group)
        offsets = parse_group_offsets(described, topic)
        self._last_group_owner = parse_group_owner(described, topic)
        return offsets

    def group_state(self, group=CONSUMER_GROUP):
        return parse_group_state(self.kafka('kafka-consumer-groups.sh', '--describe', '--group', group, '--state'))

    def committed(self, topic=DOMAIN_TOPIC):
        # A group that never committed resumes from the log start (auto-offset-reset=earliest),
        # which is offset 0 on the fresh, never-truncated topics of a disposable project.
        return self.group_offsets(topic)[0] or 0

    def committed_past(self, offset, topic=DOMAIN_TOPIC):
        """The committed offset once the group has acknowledged the record at `offset`, else None."""
        committed = self.committed(topic)
        return committed if committed > offset else None

    def end_offset(self, topic):
        rows = [line.split(':') for line in self.kafka('kafka-get-offsets.sh', '--topic', topic).splitlines()]
        assert len(rows) == 1 and rows[0][1] == '0', f'{topic} must have exactly one partition: {rows}'
        return int(rows[0][2])

    def publish(self, topic, key, value, headers=None):
        line, properties = f'{key}|{value}', ['--property', 'parse.key=true', '--property', 'key.separator=|']
        if headers:
            line = ','.join(f'{name}:{header}' for name, header in headers.items()) + '\t' + line
            properties += ['--property', 'parse.headers=true']
        self.kafka('kafka-console-producer.sh', '--topic', topic, *properties, text=line + '\n')

    def records(self, topic, start, end):
        """All records in [start, end) of partition 0 as (offset, key, value)."""
        if end <= start:
            return []
        records = parse_console_records(self.kafka(
            'kafka-console-consumer.sh', '--topic', topic, '--partition', '0', '--offset', str(start),
            '--max-messages', str(end - start), '--timeout-ms', '15000',
            '--property', 'print.offset=true', '--property', 'print.key=true'))
        assert [r[0] for r in records] == list(range(start, end)), f'{topic} [{start}, {end}): {records}'
        return records


class ComposeStack(Stack):
    """One disposable qualification Compose project; app containers have restart policy `no`, so
    every restart is the drill's own `docker start`."""

    backend = 'compose'

    def __init__(self, project):
        if project not in ALLOWED_PROJECTS:
            raise ValueError(f'{project} is not a disposable qualification project')
        self.project = project
        self.ids = {}
        for service in ('postgres', 'kafka', *APP_SERVICES):
            ids = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
                       '--filter', 'label=com.docker.compose.service=' + service]).splitlines()
            assert len(ids) == 1, f'{project}/{service} must have exactly one running container: {ids}'
            info = self.inspect(ids[0])
            assert info['Config']['Labels']['com.docker.compose.project'] == project
            if service in APP_SERVICES:
                assert info['Config']['Image'].startswith('materialflow-qualification/'), info['Config']['Image']
                assert info['HostConfig']['RestartPolicy']['Name'] == 'no', 'App restart policy must be "no"'
            self.ids[service] = ids[0]

    @staticmethod
    def inspect(container):
        return json.loads(run(['docker', 'inspect', container]))[0]

    def psql(self, *args, app_name=None):
        env = ['-e', 'PGAPPNAME=' + app_name] if app_name else []
        return ['docker', 'exec', *env, self.ids['postgres'], 'psql', '-U', 'materialflow', '-d', 'materialflow',
                *args]

    def kafka_command(self, executable, interactive):
        return ['docker', 'exec', '-i', self.ids['kafka'], '/opt/kafka/bin/' + executable]

    def images(self):
        return {service: dict(image=info['Config']['Image'], image_id=info['Image'])
                for service, info in ((s, self.inspect(self.ids[s])) for s in APP_SERVICES)}

    def profile_active(self, service, profile):
        env = dict(item.split('=', 1) for item in self.inspect(self.ids[service])['Config']['Env'])
        return profile in env.get('SPRING_PROFILES_ACTIVE', '').split(',')

    def running(self, service):
        return self.inspect(self.ids[service])['State']['Running']

    def kill(self, service, event_id=None):
        cid = self.ids[service]
        before = self.inspect(cid)['State']
        assert before['Running'] and before['Pid'] > 0, before
        run(['docker', 'kill', '--signal=KILL', cid])
        after = self.inspect(cid)['State']
        assert not after['Running'] and after['ExitCode'] == 137, after
        return dict(container_id=cid[:12], pid_before=before['Pid'], started_before=before['StartedAt'],
                    exit_code=after['ExitCode'], oom_killed=after['OOMKilled'])

    def restart(self, service, process, killed):
        """`docker start`; returns kill -> container started, in ms."""
        run(['docker', 'start', self.ids[service]])
        state = self.inspect(self.ids[service])['State']
        assert state['Running'] and state['StartedAt'] != process['started_before'], state
        process.update(pid_after=state['Pid'], started_after=state['StartedAt'])
        return round((time.monotonic() - killed) * 1000)

    def erp_core_counters(self, name):
        """{(container, JVM start): value} of one erp-core counter, or None when the Prometheus
        endpoint is not readable (JWT-protected stacks do not expose metrics to the drill)."""
        try:
            exposition = run(['docker', 'exec', self.ids['erp-core'], 'wget', '-qO-',
                              'http://localhost:8080/actuator/prometheus'])
        except RuntimeError:
            return None
        started = self.inspect(self.ids['erp-core'])['State']['StartedAt']
        return {(self.ids['erp-core'][:12], started): parse_prometheus_counter(exposition, name)}
