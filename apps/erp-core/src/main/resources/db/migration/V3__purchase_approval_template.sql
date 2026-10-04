INSERT INTO approval_templates
    (id, organization_id, object_type, template_version, mode, required_approvals,
     amount_threshold, max_discount_rate, approver_ids)
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111',
        'PURCHASE_REQUEST', 1, 'ANY', 1, 5000.00, NULL,
        '["66666666-6666-6666-6666-666666666602"]'::jsonb);

