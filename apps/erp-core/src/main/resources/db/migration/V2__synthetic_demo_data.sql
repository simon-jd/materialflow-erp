INSERT INTO organizations (id, code, name)
VALUES ('11111111-1111-1111-1111-111111111111', 'MF-DEMO', 'MaterialFlow Synthetic Building Supply');

INSERT INTO warehouses (id, organization_id, code, name, region_code)
VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
        'WH-WEST-01', 'Synthetic West Distribution Center', 'WEST');

INSERT INTO customers (id, organization_id, code, name, region_code, payment_terms_days)
VALUES ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111',
        'CUST-001', 'Cedar Ridge Contractors', 'WEST', 30);

INSERT INTO suppliers (id, organization_id, code, name)
VALUES ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
        'SUP-001', 'Northstar Synthetic Materials');

INSERT INTO materials (id, organization_id, sku, name, specification, grade, color, base_unit)
VALUES
    ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
     'CEM-42-5', 'Portland Cement', '42.5 MPa / 25 kg bag', 'A', 'GRAY', 'PIECE'),
    ('55555555-5555-5555-5555-555555555556', '11111111-1111-1111-1111-111111111111',
     'PLY-18-2440', 'Structural Plywood', '18 mm x 1220 x 2440 mm', 'B', 'NATURAL', 'PIECE');

INSERT INTO app_users (id, organization_id, external_subject, display_name)
VALUES
    ('66666666-6666-6666-6666-666666666601', '11111111-1111-1111-1111-111111111111', 'demo-sales', 'Synthetic Sales Maker'),
    ('66666666-6666-6666-6666-666666666602', '11111111-1111-1111-1111-111111111111', 'demo-approver', 'Synthetic Finance Checker'),
    ('66666666-6666-6666-6666-666666666603', '11111111-1111-1111-1111-111111111111', 'demo-warehouse', 'Synthetic Warehouse Operator');

INSERT INTO user_roles (organization_id, user_id, role_code, warehouse_id)
VALUES
    ('11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-666666666601', 'SALES_ORDER_WRITE', NULL),
    ('11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-666666666602', 'APPROVAL_DECIDE', NULL),
    ('11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-666666666603', 'WAREHOUSE_OPERATE', '22222222-2222-2222-2222-222222222222');

INSERT INTO credit_accounts (id, organization_id, customer_id, credit_limit)
VALUES ('77777777-7777-7777-7777-777777777777', '11111111-1111-1111-1111-111111111111',
        '33333333-3333-3333-3333-333333333333', 100000.00);

INSERT INTO price_rules
    (id, organization_id, source, material_id, customer_id, region_code, unit_price, discount_rate, rule_version, valid_from)
VALUES
    ('88888888-8888-8888-8888-888888888801', '11111111-1111-1111-1111-111111111111', 'STANDARD',
     '55555555-5555-5555-5555-555555555555', NULL, NULL, 11.5000, 0, 1, '2026-01-01T00:00:00Z'),
    ('88888888-8888-8888-8888-888888888802', '11111111-1111-1111-1111-111111111111', 'CONTRACT',
     '55555555-5555-5555-5555-555555555555', '33333333-3333-3333-3333-333333333333', NULL,
     10.7500, 0.025000, 3, '2026-01-01T00:00:00Z'),
    ('88888888-8888-8888-8888-888888888803', '11111111-1111-1111-1111-111111111111', 'STANDARD',
     '55555555-5555-5555-5555-555555555556', NULL, NULL, 47.2500, 0, 1, '2026-01-01T00:00:00Z');

INSERT INTO inventory_balances
    (id, organization_id, warehouse_id, material_id, location_id, on_hand, reserved, in_transit)
VALUES
    ('99999999-9999-9999-9999-999999999901', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555555',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 1000, 0, 0),
    ('99999999-9999-9999-9999-999999999902', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555556',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 500, 0, 0);

INSERT INTO approval_templates
    (id, organization_id, object_type, template_version, mode, required_approvals,
     amount_threshold, max_discount_rate, approver_ids)
VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111',
        'SALES_ORDER', 1, 'ANY', 1, 25000.00, 0.100000,
        '["66666666-6666-6666-6666-666666666602"]'::jsonb);

