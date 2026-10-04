#!/usr/bin/env python3
"""Read deployed health/projection/trace and invoke the local synthetic gRPC adapter.

Run jwt-rbac-e2e.sh first; pass its created order ID. No additional orders are made.
"""
import argparse
import datetime
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def request(url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def wait(probe, timeout=90):
    deadline = time.monotonic()+timeout
    error = None
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (urllib.error.URLError, ValueError, AssertionError) as exc:
            error = exc
        time.sleep(1)
    raise AssertionError(f'Qualification condition not met: {error}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', choices=['materialflow-qualification', 'materialflow-qualification-boot4',
                                             'materialflow-qualification-final'],
                        default='materialflow-qualification-final')
    parser.add_argument('--order-id', type=uuid.UUID, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'Refusing to overwrite evidence'
    encoded = urllib.parse.urlencode(dict(client_id='materialflow-cli', grant_type='password',
                                         username='synthetic-demo', password='synthetic-demo-only')).encode()
    token = request('http://localhost:28180/realms/materialflow/protocol/openid-connect/token', encoded)['access_token']
    headers = {'Authorization': 'Bearer '+token}
    health = {}
    for service, port in [('erp-core', 28080), ('edge-gateway', 28081), ('stream-projection', 28082),
                          ('integration-adapter', 28083), ('event-relay', 28084)]:
        value = wait(lambda: request(f'http://localhost:{port}/actuator/health', headers=headers))
        assert value['status'] == 'UP', (service, value)
        health[service] = value['status']
    projection = wait(lambda: request(f'http://localhost:28081/api/v1/projections/aggregates/{args.order_id}',
                                      headers=headers))
    assert projection['aggregateId'] == str(args.order_id)
    assert projection['organizationId'] == '11111111-1111-1111-1111-111111111111'
    assert projection['aggregateVersion'] >= 1
    po = uuid.uuid4()
    confirmation = request(f'http://localhost:28083/api/v1/simulators/suppliers/purchase-orders/{po}/confirm'
                           '?supplierId=77777777-7777-7777-7777-777777777777', b'')
    assert confirmation['purchaseOrderId'] == str(po) and confirmation['status'] == 'CONFIRMED'
    cid = subprocess.check_output(['docker', 'ps', '-q', '--filter',
                                  'label=com.docker.compose.project='+args.project,
                                  '--filter', 'label=com.docker.compose.service=postgres'], text=True).strip()
    assert cid and '\n' not in cid
    parent = subprocess.check_output(['docker', 'exec', cid, 'psql', '-XAt', '-U', 'materialflow',
                                     '-d', 'materialflow', '-c',
                                     f"SELECT trace_parent FROM outbox_events WHERE aggregate_id='{args.order_id}' "
                                     "AND trace_parent IS NOT NULL ORDER BY occurred_at LIMIT 1"], text=True).strip()
    trace_id = parent.split('-')[1]
    expected = {'materialflow-edge-gateway', 'materialflow-erp-core', 'materialflow-event-relay'}

    def exported_trace():
        data = request('http://localhost:26686/api/traces/'+trace_id)['data']
        if not data:
            return None
        trace = data[0]
        services = {p['serviceName'] for p in trace['processes'].values()}
        if not expected <= services or not any(s['operationName'] == 'domain event consume' for s in trace['spans']):
            return None
        return dict(trace_id=trace_id, services=sorted(services),
                    operation_names=sorted({s['operationName'] for s in trace['spans']}),
                    span_count=len(trace['spans']))

    trace = wait(exported_trace)
    result = dict(status='passed', project=args.project, scope='local synthetic Docker',
                  checked_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), health=health,
                  projection=dict(aggregate_id=str(args.order_id), version=projection['aggregateVersion']),
                  synthetic_grpc=dict(status=confirmation['status'], purchase_order_id=str(po)), trace=trace)
    with args.output.open('x') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    print(json.dumps(result, indent=2))
