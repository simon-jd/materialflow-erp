#!/usr/bin/env python3
"""Real SIGKILL drills, restricted to the disposable local qualification projects.

Database trigger gates make each crash window observable. They are fixture-only,
event-specific, removed in finally, and never part of a Flyway migration.
"""
import argparse
import datetime
import json
import subprocess
import time
import uuid
from pathlib import Path


def run(args, text=None, timeout=30):
    return subprocess.run(args, input=text, text=True, capture_output=True,
                          check=True, timeout=timeout).stdout.strip()


def eventually(probe, description, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(.25)
    raise AssertionError('Timed out: ' + description)


class Drill:
    def __init__(self, project):
        self.project = project
        self.ids = {}
        for service in ('postgres', 'kafka', 'erp-core', 'event-relay'):
            ids = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project='+project,
                       '--filter', 'label=com.docker.compose.service='+service]).splitlines()
            assert len(ids) == 1, (service, ids)
            info = json.loads(run(['docker', 'inspect', ids[0]]))[0]
            assert info['Config']['Labels']['com.docker.compose.project'] == project
            if service in ('erp-core', 'event-relay'):
                assert info['Config']['Image'].startswith('materialflow-qualification/')
                assert info['HostConfig']['RestartPolicy']['Name'] == 'no'
            self.ids[service] = ids[0]

    def sql(self, statement):
        return run(['docker', 'exec', self.ids['postgres'], 'psql', '-XAt', '-v',
                    'ON_ERROR_STOP=1', '-U', 'materialflow', '-d', 'materialflow', '-c', statement])

    def kafka(self, executable, *args, text=None):
        return run(['docker', 'exec', '-i', self.ids['kafka'], '/opt/kafka/bin/'+executable,
                    '--bootstrap-server', 'localhost:9092', *args], text, timeout=45)

    def offset(self):
        output = self.kafka('kafka-consumer-groups.sh', '--describe', '--group', 'erp-core-reallocation-v1')
        rows = [line.split() for line in output.splitlines()]
        offsets = [int(r[3]) for r in rows if len(r) > 4 and r[1] == 'materialflow.domain-events.v1'
                   and r[2] == '0' and r[3].isdigit()]
        assert len(offsets) == 1, output
        return offsets[0]

    def envelope(self, event_id):
        return dict(eventId=event_id, organizationId='11111111-1111-1111-1111-111111111111',
                    eventType='QualificationProbe', aggregateId=event_id, aggregateVersion=1,
                    schemaVersion=1, occurredAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    payload={})

    def publish(self, topic, event_id):
        self.kafka('kafka-console-producer.sh', '--topic', topic, '--property', 'parse.key=true',
                   '--property', 'key.separator=|', text=event_id+'|'+json.dumps(self.envelope(event_id))+'\n')

    def gate(self, table, column, event_id, condition):
        name = 'mf_qualification_'+uuid.uuid4().hex
        key = uuid.uuid4().int % (2**62)
        holder = subprocess.Popen(['docker', 'exec', '-e', 'PGAPPNAME='+name, self.ids['postgres'],
                                   'psql', '-XAt', '-U', 'materialflow', '-d', 'materialflow', '-c',
                                   f'SELECT pg_advisory_lock({key}); SELECT pg_sleep(180)'],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        eventually(lambda: self.sql(f"SELECT count(*) FROM pg_stat_activity WHERE application_name='{name}' "
                                    "AND wait_event='PgSleep'") == '1', 'fixture owns advisory lock')
        self.sql(f"CREATE FUNCTION {name}() RETURNS trigger LANGUAGE plpgsql AS $$ "
                 f"BEGIN PERFORM pg_advisory_xact_lock({key}); RETURN NEW; END $$; "
                 f"CREATE TRIGGER {name} AFTER {condition} ON {table} FOR EACH ROW "
                 f"WHEN (NEW.{column}='{event_id}'::uuid) EXECUTE FUNCTION {name}()")
        return name, holder, table

    def wait_gate(self):
        eventually(lambda: int(self.sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' "
                                       "AND NOT granted")) == 1, 'application blocked in fixture trigger')

    def release(self, gate):
        name, holder, table = gate
        self.sql(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{name}'")
        holder.communicate(timeout=10)
        self.sql(f'DROP TRIGGER IF EXISTS {name} ON {table}; DROP FUNCTION IF EXISTS {name}()')

    def kill(self, service):
        cid = self.ids[service]
        before = json.loads(run(['docker', 'inspect', cid]))[0]['State']
        assert before['Running'] and before['Pid'] > 0
        run(['docker', 'kill', '--signal=KILL', cid])
        after = json.loads(run(['docker', 'inspect', cid]))[0]['State']
        assert not after['Running'] and after['ExitCode'] == 137, after
        return dict(container_id=cid, pid_before=before['Pid'], started_before=before['StartedAt'],
                    exit_code=after['ExitCode'], oom_killed=after['OOMKilled'])

    def restart(self, service, result):
        run(['docker', 'start', self.ids[service]])
        state = json.loads(run(['docker', 'inspect', self.ids[service]]))[0]['State']
        assert state['Running'] and state['StartedAt'] != result['started_before']
        result.update(pid_after=state['Pid'], started_after=state['StartedAt'])

    def consumer(self):
        topic = 'materialflow.domain-events.v1'
        end = self.kafka('kafka-get-offsets.sh', '--topic', topic).splitlines()
        assert len(end) == 1 and end[0].split(':')[1] == '0', 'Requires one quiescent partition'
        baseline = int(end[0].split(':')[2])
        eventually(lambda: self.offset() == baseline, 'consumer caught up before injection')
        event_id = str(uuid.uuid4())
        gate = self.gate('consumer_inbox', 'event_id', event_id, 'INSERT')
        killed = None
        try:
            self.publish(topic, event_id)
            self.wait_gate()
            assert self.sql(f"SELECT count(*) FROM consumer_inbox WHERE event_id='{event_id}'") == '0'
            assert self.offset() == baseline
            killed = self.kill('erp-core')
            assert self.sql(f"SELECT count(*) FROM consumer_inbox WHERE event_id='{event_id}'") == '0'
            assert self.offset() == baseline
        finally:
            self.release(gate)
            if killed:
                self.restart('erp-core', killed)
        eventually(lambda: self.sql(f"SELECT count(*) FROM consumer_inbox WHERE event_id='{event_id}'") == '1',
                   'restarted consumer commits retained event')
        eventually(lambda: self.offset() == baseline+1, 'first offset committed')
        self.publish(topic, event_id)
        eventually(lambda: self.offset() == baseline+2, 'duplicate Kafka delivery acknowledged')
        count = int(self.sql(f"SELECT count(*) FROM consumer_inbox WHERE event_id='{event_id}'"))
        assert count == 1
        return dict(status='passed', window='Inbox INSERT blocked before transaction commit; source offset retained',
                    event_id=event_id, offset_before=baseline, offset_after=baseline+2,
                    inbox_rows_after_replay=count, process=killed,
                    limitation='Probe has no inventory effect; inventory-effect atomicity is covered separately by JUnit.')

    def relay(self):
        event_id = str(uuid.uuid4())
        topic = 'materialflow.qualification.'+event_id
        self.kafka('kafka-topics.sh', '--create', '--topic', topic, '--partitions', '1', '--replication-factor', '1')
        # The gate is entered only for the ACK status write, after kafka.send(...).get() returned.
        gate = self.gate('outbox_events', 'id', event_id, "UPDATE OF published_at")
        payload = json.dumps(self.envelope(event_id))
        killed = None
        try:
            self.sql("INSERT INTO outbox_events (id, organization_id, topic, event_type, aggregate_id, "
                     "aggregate_version, schema_version, event_key, payload, trace_id, correlation_id, occurred_at) "
                     f"VALUES ('{event_id}', '11111111-1111-1111-1111-111111111111', '{topic}', "
                     f"'QualificationProbe', '{event_id}', 1, 1, '{event_id}', '{payload}'::jsonb, "
                     f"'qualification', '{event_id}', CURRENT_TIMESTAMP)")
            self.wait_gate()
            row = self.sql(f"SELECT status || ':' || attempt_count || ':' || claim_token FROM outbox_events WHERE id='{event_id}'")
            assert row.startswith('CLAIMED:1:'), row
            first = self.kafka('kafka-console-consumer.sh', '--topic', topic, '--partition', '0', '--offset', '0',
                               '--max-messages', '1', '--timeout-ms', '10000')
            assert json.loads(first)['eventId'] == event_id
            killed = self.kill('event-relay')
            assert self.sql(f"SELECT status FROM outbox_events WHERE id='{event_id}'") == 'CLAIMED'
        finally:
            self.release(gate)
            if killed:
                self.restart('event-relay', killed)
        eventually(lambda: self.sql(f"SELECT status FROM outbox_events WHERE id='{event_id}'") == 'PUBLISHED',
                   'expired claim republished after actual JVM death')
        records = self.kafka('kafka-console-consumer.sh', '--topic', topic, '--partition', '0', '--offset', '0',
                             '--max-messages', '2', '--timeout-ms', '10000', '--property', 'print.offset=true').splitlines()
        assert len(records) == 2
        assert [line.split('\t')[0] for line in records] == ['Offset:0', 'Offset:1'], records
        assert all(json.loads(line.split('\t', 1)[1])['eventId'] == event_id for line in records)
        attempts = int(self.sql(f"SELECT attempt_count FROM outbox_events WHERE id='{event_id}'"))
        assert attempts == 2
        return dict(status='passed', window='Kafka ACK observed, PostgreSQL PUBLISHED transaction blocked before commit',
                    event_id=event_id, topic=topic, claim_before=row, final_status='PUBLISHED', attempts=attempts,
                    kafka_offsets=[0, 1], process=killed,
                    limitation='At-least-once replay is expected; two broker records are not exactly-once delivery.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', choices=['materialflow-qualification', 'materialflow-qualification-boot4',
                                             'materialflow-qualification-final'],
                        default='materialflow-qualification-final')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    # Reserve evidence destination before touching any container.
    with args.output.open('x') as output:
        result = dict(project=args.project, scope='local synthetic Docker, real JVM SIGKILL',
                      started_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        try:
            drill = Drill(args.project)
            result['consumer'] = drill.consumer()
            result['relay'] = drill.relay()
            result['status'] = 'passed'
        except Exception as exc:
            result['status'] = 'failed'
            result['error'] = str(exc)
            raise
        finally:
            result['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            json.dump(result, output, indent=2)
            output.write('\n')
