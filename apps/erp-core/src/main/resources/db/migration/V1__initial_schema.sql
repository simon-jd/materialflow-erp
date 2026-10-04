CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    external_subject VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, external_subject)
);

CREATE TABLE user_roles (
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    role_code VARCHAR(80) NOT NULL,
    warehouse_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, user_id, role_code)
);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    region_code VARCHAR(40) NOT NULL,
    payment_terms_days INTEGER NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, code)
);

CREATE TABLE suppliers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, code)
);

CREATE TABLE warehouses (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    region_code VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, code)
);

ALTER TABLE user_roles
    ADD CONSTRAINT fk_user_roles_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

CREATE TABLE materials (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    sku VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    specification VARCHAR(200),
    grade VARCHAR(80),
    color VARCHAR(80),
    base_unit VARCHAR(32) NOT NULL CHECK (base_unit IN ('PIECE', 'BOX', 'PALLET', 'METER', 'SQUARE_METER', 'TON')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, sku)
);

CREATE TABLE price_rules (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    source VARCHAR(32) NOT NULL CHECK (source IN ('CONTRACT', 'CUSTOMER_SPECIAL', 'REGIONAL', 'STANDARD')),
    material_id UUID NOT NULL REFERENCES materials(id),
    customer_id UUID REFERENCES customers(id),
    region_code VARCHAR(40),
    unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    discount_rate NUMERIC(9,6) NOT NULL DEFAULT 0 CHECK (discount_rate BETWEEN 0 AND 1),
    rule_version BIGINT NOT NULL CHECK (rule_version > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE INDEX idx_price_rules_lookup
    ON price_rules (organization_id, material_id, source, customer_id, region_code, valid_from DESC)
    WHERE active;

CREATE TABLE credit_accounts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    credit_limit NUMERIC(19,2) NOT NULL CHECK (credit_limit >= 0),
    credit_reserved NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (credit_reserved >= 0),
    credit_used NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (credit_used >= 0),
    overdue_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (overdue_amount >= 0),
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, customer_id)
);

CREATE TABLE credit_ledger (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    credit_account_id UUID NOT NULL REFERENCES credit_accounts(id),
    order_id UUID,
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('RESERVE', 'RELEASE', 'CAPTURE', 'SETTLE', 'ADJUST')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    balance_reserved NUMERIC(19,2) NOT NULL,
    balance_used NUMERIC(19,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE approval_templates (
    id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    object_type VARCHAR(40) NOT NULL,
    template_version BIGINT NOT NULL CHECK (template_version > 0),
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('ANY', 'ALL', 'N_OF_M')),
    required_approvals INTEGER NOT NULL CHECK (required_approvals > 0),
    amount_threshold NUMERIC(19,2),
    max_discount_rate NUMERIC(9,6),
    approver_ids JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, template_version)
);

CREATE TABLE approval_instances (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    object_type VARCHAR(40) NOT NULL,
    object_id UUID NOT NULL,
    template_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    applicant_id UUID NOT NULL REFERENCES app_users(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    mode VARCHAR(20) NOT NULL,
    required_approvals INTEGER NOT NULL,
    rule_inputs JSONB NOT NULL,
    resolved_approvers JSONB NOT NULL,
    approved_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (template_id, template_version) REFERENCES approval_templates(id, template_version),
    UNIQUE (organization_id, object_type, object_id)
);

CREATE TABLE approval_decisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    approval_instance_id UUID NOT NULL REFERENCES approval_instances(id),
    approver_id UUID NOT NULL REFERENCES app_users(id),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (approval_instance_id, approver_id)
);

CREATE TABLE sales_orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_number VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL REFERENCES customers(id),
    applicant_id UUID NOT NULL REFERENCES app_users(id),
    status VARCHAR(40) NOT NULL,
    allow_partial_fulfillment BOOLEAN NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL CHECK (total_amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    approval_instance_id UUID REFERENCES approval_instances(id),
    credit_override_approved BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, order_number)
);

ALTER TABLE credit_ledger
    ADD CONSTRAINT fk_credit_ledger_order FOREIGN KEY (order_id) REFERENCES sales_orders(id);

CREATE TABLE sales_order_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_id UUID NOT NULL REFERENCES sales_orders(id),
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    material_id UUID NOT NULL REFERENCES materials(id),
    requested_warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    location_id UUID NOT NULL,
    unit VARCHAR(32) NOT NULL,
    ordered_quantity NUMERIC(19,6) NOT NULL CHECK (ordered_quantity > 0),
    allocated_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (allocated_quantity >= 0),
    fulfilled_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (fulfilled_quantity >= 0),
    invoiced_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (invoiced_quantity >= 0),
    unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    discount_rate NUMERIC(9,6) NOT NULL CHECK (discount_rate BETWEEN 0 AND 1),
    net_amount NUMERIC(19,2) NOT NULL CHECK (net_amount >= 0),
    price_source VARCHAR(32) NOT NULL,
    price_source_id UUID,
    price_rule_version BIGINT NOT NULL,
    override_reason VARCHAR(500),
    approval_reference UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_id, line_number),
    CHECK (fulfilled_quantity <= allocated_quantity),
    CHECK (invoiced_quantity <= fulfilled_quantity)
);

CREATE TABLE inventory_balances (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    material_id UUID NOT NULL REFERENCES materials(id),
    location_id UUID NOT NULL,
    on_hand NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    available NUMERIC(19,6) GENERATED ALWAYS AS (on_hand - reserved) STORED,
    in_transit NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (in_transit >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, warehouse_id, material_id, location_id)
);

CREATE INDEX idx_inventory_hot_path
    ON inventory_balances (organization_id, warehouse_id, material_id, location_id);

CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_id UUID NOT NULL REFERENCES sales_orders(id),
    order_line_id UUID NOT NULL REFERENCES sales_order_lines(id),
    inventory_balance_id UUID NOT NULL REFERENCES inventory_balances(id),
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    shipped_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (shipped_quantity >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'PARTIALLY_SHIPPED', 'SHIPPED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_line_id, inventory_balance_id),
    CHECK (shipped_quantity <= quantity)
);

CREATE TABLE inventory_ledger (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    inventory_balance_id UUID NOT NULL REFERENCES inventory_balances(id),
    reference_type VARCHAR(40) NOT NULL,
    reference_id UUID NOT NULL,
    movement_type VARCHAR(30) NOT NULL CHECK (movement_type IN ('RECEIPT', 'TRANSFER_IN', 'TRANSFER_OUT', 'RESERVE', 'RELEASE', 'SHIP', 'ADJUST')),
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    on_hand_after NUMERIC(19,6) NOT NULL,
    reserved_after NUMERIC(19,6) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE procurement_suggestions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_id UUID REFERENCES sales_orders(id),
    order_line_id UUID REFERENCES sales_order_lines(id),
    material_id UUID NOT NULL REFERENCES materials(id),
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    shortage_quantity NUMERIC(19,6) NOT NULL CHECK (shortage_quantity > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONVERTED', 'DISMISSED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_line_id)
);

CREATE TABLE purchase_requests (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    request_number VARCHAR(64) NOT NULL,
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    applicant_id UUID NOT NULL REFERENCES app_users(id),
    status VARCHAR(30) NOT NULL,
    total_amount NUMERIC(19,2) NOT NULL CHECK (total_amount >= 0),
    approval_instance_id UUID REFERENCES approval_instances(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, request_number)
);

CREATE TABLE purchase_request_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    purchase_request_id UUID NOT NULL REFERENCES purchase_requests(id),
    suggestion_id UUID REFERENCES procurement_suggestions(id),
    material_id UUID NOT NULL REFERENCES materials(id),
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    location_id UUID NOT NULL,
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(19,4) NOT NULL CHECK (unit_cost >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    purchase_order_number VARCHAR(64) NOT NULL,
    purchase_request_id UUID NOT NULL UNIQUE REFERENCES purchase_requests(id),
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    status VARCHAR(30) NOT NULL,
    supplier_confirmation VARCHAR(120),
    expected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, purchase_order_number)
);

CREATE TABLE purchase_order_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders(id),
    purchase_request_line_id UUID NOT NULL UNIQUE REFERENCES purchase_request_lines(id),
    material_id UUID NOT NULL REFERENCES materials(id),
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    location_id UUID NOT NULL,
    ordered_quantity NUMERIC(19,6) NOT NULL CHECK (ordered_quantity > 0),
    received_quantity NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_cost NUMERIC(19,4) NOT NULL CHECK (unit_cost >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (received_quantity <= ordered_quantity)
);

CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_id UUID NOT NULL REFERENCES sales_orders(id),
    shipment_number VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, shipment_number)
);

CREATE TABLE shipment_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    order_line_id UUID NOT NULL REFERENCES sales_order_lines(id),
    reservation_id UUID NOT NULL REFERENCES inventory_reservations(id),
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (shipment_id, reservation_id)
);

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    order_id UUID NOT NULL REFERENCES sales_orders(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    invoice_number VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'RECONCILIATION_EXCEPTION', 'VOID')),
    total_amount NUMERIC(19,2) NOT NULL CHECK (total_amount >= 0),
    paid_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    invoice_date DATE NOT NULL,
    due_date DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, invoice_number),
    CHECK (paid_amount <= total_amount)
);

CREATE TABLE invoice_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    order_line_id UUID NOT NULL REFERENCES sales_order_lines(id),
    shipment_line_id UUID NOT NULL UNIQUE REFERENCES shipment_lines(id),
    quantity NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    amount NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_files (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    file_name VARCHAR(255) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    record_count INTEGER NOT NULL CHECK (record_count BETWEEN 1 AND 10000),
    imported_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, content_hash)
);

CREATE TABLE bank_transactions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    payment_file_id UUID NOT NULL REFERENCES payment_files(id),
    line_number INTEGER NOT NULL,
    external_reference VARCHAR(120) NOT NULL,
    invoice_reference VARCHAR(64),
    order_reference VARCHAR(64),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    value_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (payment_file_id, line_number),
    UNIQUE (organization_id, external_reference)
);

CREATE TABLE payment_allocations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    bank_transaction_id UUID NOT NULL REFERENCES bank_transactions(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    approved_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bank_transaction_id, invoice_id)
);

CREATE TABLE reconciliation_exceptions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    bank_transaction_id UUID NOT NULL REFERENCES bank_transactions(id),
    reason_code VARCHAR(40) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolved_by UUID REFERENCES app_users(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bank_transaction_id)
);

CREATE TABLE idempotency_records (
    organization_id UUID NOT NULL REFERENCES organizations(id),
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response_status INTEGER,
    response_body JSONB,
    resource_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, operation, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_records (expires_at);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    actor_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    before_state JSONB,
    after_state JSONB,
    reason VARCHAR(500),
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_aggregate ON audit_log (organization_id, aggregate_type, aggregate_id, created_at);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    topic VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    causation_id UUID,
    correlation_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_by VARCHAR(160),
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_claim ON outbox_events (status, next_attempt_at, occurred_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE TABLE consumer_inbox (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(120) NOT NULL,
    organization_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_name)
);

