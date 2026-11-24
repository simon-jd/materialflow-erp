"""The four crash windows. Each run injects one event, parks the target JVM inside the
window, SIGKILLs it, restarts it and waits for the window's invariant.

Windows 1 and 3 park the application on a fixture trigger inside its own database
transaction. Windows 2 and 4 have no database write between the side effect and the
offset commit, so erp-core must run with the `fault-injection` profile, whose
ConsumerCrashWindowGate waits on the drill's advisory lock when a record carries the
window's header (see apps/erp-core/.../events/AdvisoryLockCrashWindowGate.java).

The windows only talk to the `Stack` interface, so they run unchanged against Compose and
Kubernetes. With several replicas the backend kills the instance that is actually parked.
"""
import datetime
import json
import time
import uuid
from dataclasses import dataclass
from typing import Callable

from .stack import DLQ_TOPIC, DOMAIN_TOPIC, counter_increase, eventually, utc_now

ORGANIZATION = '11111111-1111-1111-1111-111111111111'
WAREHOUSE = '22222222-2222-2222-2222-222222222222'
CUSTOMER = '33333333-3333-3333-3333-333333333333'
SUPPLIER = '44444444-4444-4444-4444-444444444444'
MATERIAL = '55555555-5555-5555-5555-555555555555'
SALES_USER = '66666666-6666-6666-6666-666666666601'
WAREHOUSE_USER = '66666666-6666-6666-6666-666666666603'
QUANTITY = 10
AFTER_INBOX_COMMIT_HEADER = 'materialflow-crash-gate-after-inbox-commit'
AFTER_DEAD_LETTER_SEND_HEADER = 'materialflow-crash-gate-after-dead-letter-send'
DUPLICATE_COUNTER = 'event_duplicate_total'


@dataclass(frozen=True)
class Window:
    number: int
    name: str
    service: str
    crash_point: str
    expected: str
    run: Callable
    needs_fault_injection: bool = False


def ms_since(start):
    return round((time.monotonic() - start) * 1000)


def envelope(event_id, event_type, aggregate_id, organization_id=ORGANIZATION, payload=None):
    value = dict(eventId=event_id, eventType=event_type, aggregateId=aggregate_id, aggregateVersion=2,
                 organizationId=organization_id, schemaVersion=1, traceId='fault-matrix',
                 occurredAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 correlationId=aggregate_id, payload=payload or {})
    if organization_id is None:
        del value['organizationId']
    return json.dumps(value, separators=(',', ':'))


@dataclass(frozen=True)
class Backorder:
    """A BACKORDERED sales order whose converted purchase order was just received (stock on hand)."""
    order_id: str
    line_id: str
    balance_id: str
    purchase_order_id: str

    @classmethod
    def seed(cls, stack):
        ids = {name: str(uuid.uuid4()) for name in
               ('order', 'line', 'balance', 'location', 'suggestion', 'request', 'request_line', 'po', 'po_line')}
        tag = ids['order'][:8].upper()
        stack.sql(f"""BEGIN;
            INSERT INTO inventory_balances (id, organization_id, warehouse_id, material_id, location_id, on_hand)
            VALUES ('{ids['balance']}', '{ORGANIZATION}', '{WAREHOUSE}', '{MATERIAL}', '{ids['location']}',
                    {QUANTITY});
            INSERT INTO sales_orders (id, organization_id, order_number, customer_id, applicant_id, status,
                                      allow_partial_fulfillment, total_amount)
            VALUES ('{ids['order']}', '{ORGANIZATION}', 'FM-SO-{tag}', '{CUSTOMER}', '{SALES_USER}', 'BACKORDERED',
                    true, 115.00);
            INSERT INTO sales_order_lines (id, organization_id, order_id, line_number, material_id,
                requested_warehouse_id, location_id, unit, ordered_quantity, unit_price, discount_rate,
                net_amount, price_source, price_rule_version)
            VALUES ('{ids['line']}', '{ORGANIZATION}', '{ids['order']}', 1, '{MATERIAL}', '{WAREHOUSE}',
                    '{ids['location']}', 'PIECE', {QUANTITY}, 11.5, 0, 115.00, 'STANDARD', 1);
            INSERT INTO procurement_suggestions (id, organization_id, order_id, order_line_id, material_id,
                                                 warehouse_id, shortage_quantity, status)
            VALUES ('{ids['suggestion']}', '{ORGANIZATION}', '{ids['order']}', '{ids['line']}', '{MATERIAL}',
                    '{WAREHOUSE}', {QUANTITY}, 'CONVERTED');
            INSERT INTO purchase_requests (id, organization_id, request_number, supplier_id, applicant_id, status,
                                           total_amount)
            VALUES ('{ids['request']}', '{ORGANIZATION}', 'FM-PR-{tag}', '{SUPPLIER}', '{SALES_USER}', 'APPROVED',
                    80.00);
            INSERT INTO purchase_request_lines (id, organization_id, purchase_request_id, suggestion_id,
                                                material_id, warehouse_id, location_id, quantity, unit_cost)
            VALUES ('{ids['request_line']}', '{ORGANIZATION}', '{ids['request']}', '{ids['suggestion']}',
                    '{MATERIAL}', '{WAREHOUSE}', '{ids['location']}', {QUANTITY}, 8);
            INSERT INTO purchase_orders (id, organization_id, purchase_order_number, purchase_request_id,
                                         supplier_id, status)
            VALUES ('{ids['po']}', '{ORGANIZATION}', 'FM-PO-{tag}', '{ids['request']}', '{SUPPLIER}', 'RECEIVED');
            INSERT INTO purchase_order_lines (id, organization_id, purchase_order_id, purchase_request_line_id,
                material_id, warehouse_id, location_id, ordered_quantity, received_quantity, unit_cost)
            VALUES ('{ids['po_line']}', '{ORGANIZATION}', '{ids['po']}', '{ids['request_line']}', '{MATERIAL}',
                    '{WAREHOUSE}', '{ids['location']}', {QUANTITY}, {QUANTITY}, 8);
            INSERT INTO inventory_ledger (id, organization_id, inventory_balance_id, reference_type, reference_id,
                movement_type, quantity, on_hand_after, reserved_after, reason, actor_id)
            VALUES ('{uuid.uuid4()}', '{ORGANIZATION}', '{ids['balance']}', 'PURCHASE_ORDER', '{ids['po']}',
                    'RECEIPT', {QUANTITY}, {QUANTITY}, 0, 'Fault-matrix fixture receipt', '{WAREHOUSE_USER}');
            COMMIT;""")
        return cls(ids['order'], ids['line'], ids['balance'], ids['po'])

    def event(self, event_id):
        return envelope(event_id, 'InventoryReceived', self.purchase_order_id,
                        payload=dict(status='RECEIVED', lineCount=1))

    def observe(self, stack, event_id):
        row = stack.sql(f"""SELECT
            (SELECT count(*) FROM consumer_inbox WHERE event_id = '{event_id}'),
            (SELECT count(*) FROM inventory_ledger WHERE reference_id = '{self.order_id}'
                AND movement_type = 'RESERVE'),
            (SELECT reserved FROM inventory_balances WHERE id = '{self.balance_id}'),
            (SELECT allocated_quantity FROM sales_order_lines WHERE id = '{self.line_id}'),
            (SELECT status FROM sales_orders WHERE id = '{self.order_id}'),
            (SELECT count(*) FROM outbox_events WHERE causation_id = '{event_id}'
                AND event_type = 'BackorderReallocated')""").split('|')
        return dict(inbox_rows=int(row[0]), business_updates=int(row[1]), reserved_quantity=float(row[2]),
                    allocated_quantity=float(row[3]), order_status=row[4], follow_up_events=int(row[5]))


def business_applied(observation, times):
    """True when the reallocation is visible exactly `times` (0 or 1) times, consistently."""
    return (observation['business_updates'] == times and observation['follow_up_events'] == times
            and observation['reserved_quantity'] == QUANTITY * times
            and observation['allocated_quantity'] == QUANTITY * times
            and observation['order_status'] == ('RESERVED' if times else 'BACKORDERED'))


def wait_consumer_idle(stack):
    """Log-end offset of the domain topic once the relay has no backlog and the consumer group
    has committed everything, i.e. the offset the next injected record will get."""
    def idle():
        if stack.scalar("SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'CLAIMED', 'FAILED')"):
            return None
        committed, end = stack.group_offsets()
        return end if (committed or 0) == end else None
    return eventually(idle, 'consumer group idle with no relay backlog', timeout=120, interval=1)


def kill_while_parked(stack, record, service, gate, inject, check_parked):
    """Inject, wait until `service` is parked on `gate`, SIGKILL it, then release and restart.

    Returns the monotonic kill time. Only a fast database check sits between the kill and
    the restart, so recovery times are not inflated by drill-side Kafka reads. The Compose
    backend restarts the container here (`restart_ms`); on Kubernetes the kubelet restarts it
    concurrently and `stack.settle` records when it was running and Ready again.
    """
    process = None
    try:
        inject()
        record['blocked_statement'] = ' '.join(stack.wait_blocked(gate).split())
        record['at_kill'] = check_parked(before_kill=True)
        process = stack.kill(service, record.get('event_id'))
        killed = time.monotonic()
        stack.forget_group_owner()  # Only an owner seen after the kill says who recovered.
        record.update(killed_at=utc_now(), process=process)
        check_parked(before_kill=False)
    finally:
        stack.release(gate)
        if process:
            record['restart_ms'] = stack.restart(service, process, killed)
    return killed


def recovered(stack, record, killed, probe, description, timeout=120):
    """Wait for the window's invariant and record kill -> invariant-restored time."""
    value = eventually(probe, description, timeout)
    record['recovery_ms'] = ms_since(killed)
    if stack.last_group_owner():
        record['recovered_owner_host'] = stack.last_group_owner()
    if record.get('restart_ms') is not None:
        record['restart_to_recovered_ms'] = record['recovery_ms'] - record['restart_ms']
    return value


def consumer_pre_commit(stack, record):
    backorder = Backorder.seed(stack)
    event_id = record['event_id'] = str(uuid.uuid4())
    record_offset = record['record_offset'] = wait_consumer_idle(stack)
    gate = stack.gate(trigger=('consumer_inbox', 'event_id', event_id, 'INSERT'))

    def check_parked(before_kill):
        observation = backorder.observe(stack, event_id)
        assert observation['inbox_rows'] == 0 and business_applied(observation, 0), observation
        if before_kill:
            observation['committed_offset'] = stack.committed()
            assert observation['committed_offset'] == record_offset, observation
        return observation

    killed = kill_while_parked(stack, record, 'erp-core', gate, lambda: stack.publish(
        DOMAIN_TOPIC, backorder.purchase_order_id, backorder.event(event_id)), check_parked)

    def restored():
        observation = backorder.observe(stack, event_id)
        if observation['inbox_rows'] == 1 and business_applied(observation, 1):
            observation['committed_offset'] = stack.committed_past(record_offset)
            if observation['committed_offset'] is not None:
                return observation
        return None
    record['after_recovery'] = recovered(stack, record, killed, restored, 'retained record committed once after restart')
    # A second delivery of the same event_id must be absorbed by the inbox key.
    stack.publish(DOMAIN_TOPIC, backorder.purchase_order_id, backorder.event(event_id))
    record['replay_offset_committed'] = wait_consumer_idle(stack)
    replay = record['after_replay'] = backorder.observe(stack, event_id)
    assert replay['inbox_rows'] == 1 and business_applied(replay, 1), replay


def absorbed_duplicates(stack, before, timeout=60):
    """How often erp-core counted the redelivered record as a duplicate absorbed by the inbox key,
    summed over its JVMs: the restarted one (Compose, one replica) or the surviving replica
    (Kubernetes, which may also see it twice if a rebalance interrupts the offset commit). None
    when the stack does not expose metrics to the drill."""
    if before is None:
        return None

    def probe():
        increase = counter_increase(before, stack.erp_core_counters(DUPLICATE_COUNTER))
        return increase if increase is not None and increase >= 1 else None
    return eventually(probe, 'redelivery counted as a duplicate by an erp-core JVM', timeout, interval=1)


def consumer_post_commit(stack, record):
    backorder = Backorder.seed(stack)
    event_id = record['event_id'] = str(uuid.uuid4())
    record_offset = record['record_offset'] = wait_consumer_idle(stack)
    counters_before = stack.erp_core_counters(DUPLICATE_COUNTER)
    gate = stack.gate()

    def check_parked(before_kill):
        observation = backorder.observe(stack, event_id)
        assert observation['inbox_rows'] == 1 and business_applied(observation, 1), observation
        if before_kill:
            observation['committed_offset'] = stack.committed()
            assert observation['committed_offset'] == record_offset, observation
        return observation

    killed = kill_while_parked(stack, record, 'erp-core', gate, lambda: stack.publish(
        DOMAIN_TOPIC, backorder.purchase_order_id, backorder.event(event_id),
        headers={AFTER_INBOX_COMMIT_HEADER: gate.key}), check_parked)
    committed = recovered(stack, record, killed, lambda: stack.committed_past(record_offset),
                          'redelivered record acknowledged after restart')
    observation = record['after_recovery'] = backorder.observe(stack, event_id)
    observation['committed_offset'] = committed
    observation['duplicates_absorbed'] = absorbed_duplicates(stack, counters_before)
    assert observation['inbox_rows'] == 1 and business_applied(observation, 1), observation
    assert observation['duplicates_absorbed'] is None or observation['duplicates_absorbed'] >= 1, observation


def relay_post_ack(stack, record):
    event_id = record['event_id'] = str(uuid.uuid4())
    topic = record['topic'] = 'materialflow.qualification.' + event_id
    stack.create_topic(topic)
    # The trigger fires only for the PUBLISHED write, which follows kafka.send(...).get().
    gate = stack.gate(trigger=('outbox_events', 'id', event_id, 'UPDATE OF published_at'))
    payload = envelope(event_id, 'QualificationProbe', event_id)

    def inject():
        stack.sql("INSERT INTO outbox_events (id, organization_id, topic, event_type, aggregate_id, "
                  "aggregate_version, schema_version, event_key, payload, trace_id, correlation_id, occurred_at) "
                  f"VALUES ('{event_id}', '{ORGANIZATION}', '{topic}', 'QualificationProbe', '{event_id}', 1, 1, "
                  f"'{event_id}', '{payload}'::jsonb, 'fault-matrix', '{event_id}', CURRENT_TIMESTAMP)")

    def check_parked(before_kill):
        row = stack.sql(f"SELECT status, attempt_count, claimed_at FROM outbox_events WHERE id = '{event_id}'").split('|')
        observation = dict(status=row[0], attempt_count=int(row[1]))
        assert observation == dict(status='CLAIMED', attempt_count=1), observation
        if before_kill:
            observation['claimed_at'] = row[2]  # The 30 s lease runs from here, not from the kill.
            observation['broker_records'] = [r[0] for r in stack.records(topic, 0, stack.end_offset(topic))]
            assert observation['broker_records'] == [0], observation
        return observation

    killed = kill_while_parked(stack, record, 'event-relay', gate, inject, check_parked)
    recovered(stack, record, killed, lambda: stack.sql(f"SELECT status FROM outbox_events WHERE id = '{event_id}'")
              == 'PUBLISHED' or None, 'expired claim republished after restart')
    row = stack.sql(f"SELECT status, attempt_count, published_at, round(extract(epoch FROM published_at - "
                    f"'{record['at_kill']['claimed_at']}'::timestamptz) * 1000) FROM outbox_events "
                    f"WHERE id = '{event_id}'").split('|')
    records = stack.records(topic, 0, stack.end_offset(topic))
    observation = record['after_recovery'] = dict(
        status=row[0], attempt_count=int(row[1]), broker_records=[r[0] for r in records],
        same_event_id=all(json.loads(r[2])['eventId'] == event_id for r in records))
    assert observation == dict(status='PUBLISHED', attempt_count=2, broker_records=[0, 1],
                               same_event_id=True), observation
    # Database clock: first claim -> republished, i.e. how much of the lease the recovery waited out.
    observation.update(published_at=row[2], claim_to_republished_ms=int(float(row[3])))


def dlq_post_ack(stack, record):
    event_id = record['event_id'] = str(uuid.uuid4())
    record_offset = record['record_offset'] = wait_consumer_idle(stack)
    dlq_start = record['dlq_start_offset'] = stack.end_offset(DLQ_TOPIC)
    gate = stack.gate()

    def dead_letters():
        records = stack.records(DLQ_TOPIC, dlq_start, stack.end_offset(DLQ_TOPIC))
        return [offset for offset, key, _ in records if key == event_id]

    def inbox_rows():
        return stack.scalar(f"SELECT count(*) FROM consumer_inbox WHERE event_id = '{event_id}'")

    def check_parked(before_kill):
        observation = dict(inbox_rows=inbox_rows())
        assert observation['inbox_rows'] == 0, observation
        if before_kill:
            observation.update(dlq_offsets=dead_letters(), committed_offset=stack.committed())
            assert len(observation['dlq_offsets']) == 1, observation
            assert observation['committed_offset'] == record_offset, observation
        return observation

    # Missing organizationId is a permanent InvalidEventEnvelopeException: no retry, straight to the DLQ.
    killed = kill_while_parked(stack, record, 'erp-core', gate, lambda: stack.publish(
        DOMAIN_TOPIC, event_id, envelope(event_id, 'InventoryReceived', event_id, organization_id=None),
        headers={AFTER_DEAD_LETTER_SEND_HEADER: gate.key}), check_parked)
    committed = recovered(stack, record, killed, lambda: stack.committed_past(record_offset),
                          'source offset advanced past the invalid record after restart')
    observation = record['after_recovery'] = dict(committed_offset=committed, dlq_offsets=dead_letters(),
                                                  inbox_rows=inbox_rows())
    # Redelivery after restart quarantines the record again: a duplicate, never a silent drop.
    assert len(observation['dlq_offsets']) == 2 and observation['inbox_rows'] == 0, observation


WINDOWS = (
    Window(1, 'consumer-pre-commit', 'erp-core',
           'Inbox INSERT blocked inside the consumer transaction, before commit',
           'After restart exactly one inbox row and one business update; source offset advances; '
           'a later duplicate delivery is absorbed', consumer_pre_commit),
    Window(2, 'consumer-post-commit', 'erp-core',
           'Inbox row and business update committed, Kafka offset not yet committed',
           'Redelivery after restart is absorbed by the inbox key: still one business update; offset advances',
           consumer_post_commit, needs_fault_injection=True),
    Window(3, 'relay-post-ack', 'event-relay',
           'Kafka send acknowledged, PUBLISHED status UPDATE not committed',
           'Claim lease expires and the event is republished: two broker records with the same event_id, '
           'PUBLISHED on attempt 2 (at-least-once)', relay_post_ack),
    Window(4, 'dlq-post-ack', 'erp-core',
           'Invalid event written to the DLQ and acknowledged, source offset not yet committed',
           'After restart the DLQ holds a duplicate and the source offset advances (no silent drop, '
           'no stuck partition)', dlq_post_ack, needs_fault_injection=True),
)
