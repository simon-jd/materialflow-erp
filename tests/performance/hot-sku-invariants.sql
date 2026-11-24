-- Post-run invariants for one hot-SKU run. psql variables: material_id, key_prefix, expected_orders.
-- Prints one JSON object; "holds" is true only if every check passes.
WITH balance AS (
    SELECT id, on_hand, reserved
      FROM inventory_balances
     WHERE material_id = :'material_id'
), lines AS (
    SELECT l.order_id, l.ordered_quantity, l.allocated_quantity, o.status, o.total_amount
      FROM sales_order_lines l JOIN sales_orders o ON o.id = l.order_id
     WHERE l.material_id = :'material_id'
), keys AS (
    SELECT status, resource_id
      FROM idempotency_records
     WHERE operation = 'sales-order.create' AND idempotency_key LIKE :'key_prefix' || '-%'
), metrics AS (
    SELECT
        (SELECT count(*) FROM balance) AS balance_rows,
        (SELECT on_hand FROM balance) AS on_hand,
        (SELECT reserved FROM balance) AS reserved,
        (SELECT count(DISTINCT order_id) FROM lines) AS orders,
        (SELECT count(*) FROM lines) AS order_lines,
        (SELECT coalesce(sum(ordered_quantity), 0) FROM lines) AS ordered_quantity,
        (SELECT coalesce(sum(allocated_quantity), 0) FROM lines) AS allocated_quantity,
        (SELECT count(*) FROM lines WHERE allocated_quantity > 0) AS lines_with_allocation,
        (SELECT count(*) FROM lines
          WHERE NOT ((status = 'RESERVED' AND allocated_quantity = ordered_quantity)
                  OR (status = 'PARTIALLY_ALLOCATED' AND allocated_quantity > 0
                      AND allocated_quantity < ordered_quantity)
                  OR (status = 'BACKORDERED' AND allocated_quantity = 0))) AS status_mismatches,
        (SELECT coalesce(sum(r.quantity), 0) FROM inventory_reservations r JOIN balance b
              ON r.inventory_balance_id = b.id WHERE r.status = 'ACTIVE') AS reservation_quantity,
        (SELECT count(*) FROM inventory_reservations r JOIN balance b
              ON r.inventory_balance_id = b.id) AS reservation_rows,
        (SELECT coalesce(sum(g.quantity), 0) FROM inventory_ledger g JOIN balance b
              ON g.inventory_balance_id = b.id WHERE g.movement_type = 'RESERVE') AS ledger_reserve_quantity,
        (SELECT count(*) FROM inventory_ledger g JOIN balance b
              ON g.inventory_balance_id = b.id WHERE g.movement_type = 'RESERVE') AS ledger_reserve_rows,
        (SELECT coalesce(sum(shortage_quantity), 0) FROM procurement_suggestions
          WHERE material_id = :'material_id') AS procurement_shortage,
        (SELECT count(*) FROM keys) AS idempotency_keys,
        (SELECT count(*) FROM keys WHERE status = 'COMPLETED') AS idempotency_completed,
        (SELECT count(DISTINCT resource_id) FROM keys) AS idempotency_distinct_orders,
        (SELECT count(DISTINCT order_id) FROM lines
          WHERE order_id NOT IN (SELECT resource_id FROM keys WHERE resource_id IS NOT NULL)) AS orders_without_key,
        (SELECT count(*) FROM outbox_events
          WHERE aggregate_id IN (SELECT order_id FROM lines)) AS outbox_events,
        (SELECT coalesce(sum(amount), 0) FROM credit_ledger
          WHERE entry_type = 'RESERVE' AND order_id IN (SELECT order_id FROM lines)) AS credit_reserved,
        (SELECT coalesce(sum(total_amount), 0) FROM sales_orders
          WHERE id IN (SELECT order_id FROM lines)) AS order_total_amount,
        (SELECT json_object_agg(status, orders ORDER BY status) FROM (
            SELECT status, count(DISTINCT order_id) AS orders FROM lines GROUP BY status) counts) AS status_counts
), checks AS (
    SELECT m.*, json_build_object(
        'single_balance_row', balance_rows = 1,
        'reserved_not_above_on_hand', reserved <= on_hand,
        'all_stock_or_all_demand_allocated', reserved = least(on_hand, ordered_quantity),
        'reserved_equals_line_allocations', reserved = allocated_quantity,
        'reserved_equals_active_reservations', reserved = reservation_quantity,
        'reserved_equals_reserve_ledger', reserved = ledger_reserve_quantity,
        'one_reservation_and_ledger_row_per_allocated_line',
            reservation_rows = lines_with_allocation AND ledger_reserve_rows = lines_with_allocation,
        'status_matches_allocation', status_mismatches = 0,
        'shortage_has_procurement_suggestion', procurement_shortage = ordered_quantity - allocated_quantity,
        'one_order_per_idempotency_key', orders = :expected_orders AND idempotency_keys = :expected_orders
            AND idempotency_completed = :expected_orders AND idempotency_distinct_orders = :expected_orders
            AND orders_without_key = 0,
        'one_outbox_event_per_order', outbox_events = orders,
        'credit_reserved_equals_order_totals', credit_reserved = order_total_amount) AS checks
      FROM metrics m
)
SELECT json_build_object(
    'material_id', :'material_id',
    'key_prefix', :'key_prefix',
    'expected_orders', :expected_orders,
    'metrics', json_build_object(
        'on_hand', on_hand, 'reserved', reserved, 'orders', orders, 'order_lines', order_lines,
        'ordered_quantity', ordered_quantity, 'allocated_quantity', allocated_quantity,
        'lines_with_allocation', lines_with_allocation, 'reservation_quantity', reservation_quantity,
        'reservation_rows', reservation_rows, 'ledger_reserve_quantity', ledger_reserve_quantity,
        'ledger_reserve_rows', ledger_reserve_rows, 'procurement_shortage', procurement_shortage,
        'idempotency_keys', idempotency_keys, 'idempotency_completed', idempotency_completed,
        'outbox_events', outbox_events, 'credit_reserved', credit_reserved,
        'order_total_amount', order_total_amount, 'status_counts', status_counts),
    'checks', checks,
    'holds', NOT EXISTS (SELECT 1 FROM json_each_text(checks) c WHERE c.value <> 'true'))
  FROM checks;
