#!/usr/bin/env python3
"""Kubernetes counterpart of qualification-smoke.py for the AKS environment.

Run tests/security/jwt-rbac-e2e.sh against the public URL first and pass its order ID. This
script creates no orders. It checks, for that one order:

  * the five services report /actuator/health UP (read inside each pod),
  * the order is readable through the public gateway with a Keycloak token,
  * its Kafka Streams projection is readable through the public gateway,
  * the synthetic gRPC adapter answers,
  * PostgreSQL holds the order, its outbox events (PUBLISHED) and their trace context,
  * the events are on the Kafka topic and erp-core's consumer recorded them in consumer_inbox,
  * Jaeger holds one trace spanning gateway -> erp-core -> event-relay -> consumer.

Usage:
  KUBECONFIG=... python3 tests/deployment/k8s-qualification-smoke.py \
    --public-url https://<host> --order-id <uuid> --output result.json
"""
import argparse
import datetime
import json
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

SERVICES = [('erp-core', 8080), ('edge-gateway', 8081), ('stream-projection', 8082),
            ('integration-adapter', 8083), ('event-relay', 8084)]
TOPIC = 'materialflow.domain-events.v1'
ORGANIZATION = '11111111-1111-1111-1111-111111111111'


def kubectl(namespace, *args, stdin=None):
    return subprocess.run(['kubectl', '-n', namespace, *args], input=stdin, text=True,
                          capture_output=True, check=True).stdout


def exec_in(namespace, target, *command):
    return kubectl(namespace, 'exec', target, '--', *command)


def http_json(url, token=None, data=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def wait(probe, timeout=120, interval=2):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (subprocess.CalledProcessError, OSError, ValueError, KeyError, AssertionError) as exc:
            last = exc
        time.sleep(interval)
    raise AssertionError(f'condition not met within {timeout}s: {last}')


def psql(namespace, sql):
    return exec_in(namespace, 'deploy/materialflow-toolbox', 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1',
                   '-F', '\t', '-c', sql).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--public-url', required=True)
    parser.add_argument('--namespace', default='materialflow')
    parser.add_argument('--order-id', type=uuid.UUID, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'Refusing to overwrite evidence'
    base = args.public_url.rstrip('/')
    ns = args.namespace
    order_id = str(args.order_id)

    form = urllib.parse.urlencode(dict(client_id='materialflow-cli', grant_type='password',
                                       username='synthetic-demo', password='synthetic-demo-only')).encode()
    token = http_json(f'{base}/idp/realms/materialflow/protocol/openid-connect/token', data=form)['access_token']

    health = {}
    for service, port in SERVICES:
        body = json.loads(exec_in(ns, f'deploy/materialflow-{service}', 'wget', '-qO-',
                                  f'http://127.0.0.1:{port}/actuator/health'))
        assert body['status'] == 'UP', (service, body)
        health[service] = body['status']

    order = http_json(f'{base}/api/v1/orders/{order_id}', token)
    assert order['id'] == order_id
    projection = wait(lambda: http_json(f'{base}/api/v1/projections/aggregates/{order_id}', token))
    assert projection['aggregateId'] == order_id and projection['organizationId'] == ORGANIZATION
    assert projection['aggregateVersion'] >= 1

    po = str(uuid.uuid4())
    confirmation = json.loads(exec_in(
        ns, 'deploy/materialflow-integration-adapter', 'wget', '-qO-', '--post-data=',
        f'http://127.0.0.1:8083/api/v1/simulators/suppliers/purchase-orders/{po}/confirm'
        '?supplierId=77777777-7777-7777-7777-777777777777'))
    assert confirmation['purchaseOrderId'] == po and confirmation['status'] == 'CONFIRMED'

    order_row = psql(ns, f"SELECT status FROM sales_orders WHERE id = '{order_id}'")
    assert order_row, 'order row missing in PostgreSQL'

    def published_events():
        rows = psql(ns, "SELECT id, event_type, status, trace_parent, published_at IS NOT NULL "
                        f"FROM outbox_events WHERE aggregate_id = '{order_id}' ORDER BY occurred_at, id")
        events = [dict(zip(['event_id', 'event_type', 'status', 'trace_parent', 'published'], r.split('\t')))
                  for r in rows.splitlines()]
        assert events and all(e['status'] == 'PUBLISHED' for e in events), events
        return events
    events = wait(published_events)

    event_ids = {e['event_id'] for e in events}

    def inbox_rows():
        ids = ','.join(f"'{e}'" for e in event_ids)
        rows = psql(ns, f'SELECT event_id, consumer_name FROM consumer_inbox WHERE event_id IN ({ids})')
        found = {r.split('\t')[0] for r in rows.splitlines()}
        assert found == event_ids, (found, event_ids)
        return [dict(zip(['event_id', 'consumer'], r.split('\t'))) for r in rows.splitlines()]
    inbox = wait(inbox_rows)

    kafka = exec_in(ns, 'kafka-0', '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'kafka:9092',
                    '--topic', TOPIC, '--from-beginning', '--timeout-ms', '15000',
                    '--property', 'print.offset=true', '--property', 'print.partition=true')
    records = [line for line in kafka.splitlines() if order_id in line]
    on_topic = {eid for eid in event_ids if any(eid in line for line in records)}
    assert on_topic == event_ids, (on_topic, event_ids)
    positions = [line.split('\t')[:2] for line in records]

    trace_id = events[0]['trace_parent'].split('-')[1]
    expected = {'materialflow-edge-gateway', 'materialflow-erp-core', 'materialflow-event-relay'}

    def exported_trace():
        data = json.loads(exec_in(ns, 'deploy/materialflow-erp-core', 'wget', '-qO-',
                                  f'http://jaeger:16686/api/traces/{trace_id}'))['data']
        trace = data[0]
        services = {p['serviceName'] for p in trace['processes'].values()}
        assert expected <= services, services
        assert any(s['operationName'] == 'domain event consume' for s in trace['spans'])
        return dict(trace_id=trace_id, services=sorted(services),
                    operation_names=sorted({s['operationName'] for s in trace['spans']}),
                    span_count=len(trace['spans']))
    trace = wait(exported_trace)

    result = dict(status='passed', scope='AKS dev environment, synthetic data', public_url=base,
                  checked_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), health=health,
                  order=dict(id=order_id, status_api=order.get('status'), status_db=order_row),
                  projection=dict(aggregate_id=order_id, version=projection['aggregateVersion']),
                  outbox=events, kafka=dict(topic=TOPIC, records=len(records), partition_offset=positions),
                  consumer_inbox=inbox,
                  synthetic_grpc=dict(status=confirmation['status'], purchase_order_id=po), trace=trace)
    with args.output.open('x') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
