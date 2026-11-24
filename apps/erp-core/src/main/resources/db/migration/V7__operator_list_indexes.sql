-- Additive read indexes for the paged operator lists: tenant first, newest first, id as tie-breaker.
CREATE INDEX idx_sales_orders_org_created
    ON sales_orders (organization_id, created_at DESC, id DESC);

CREATE INDEX idx_sales_orders_org_status_created
    ON sales_orders (organization_id, status, created_at DESC, id DESC);

CREATE INDEX idx_approval_instances_org_status_created
    ON approval_instances (organization_id, object_type, status, created_at DESC, id DESC);
