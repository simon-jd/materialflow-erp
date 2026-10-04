ALTER TABLE procurement_suggestions
    DROP CONSTRAINT procurement_suggestions_status_check;

ALTER TABLE procurement_suggestions
    ADD CONSTRAINT procurement_suggestions_status_check
        CHECK (status IN ('OPEN', 'CONVERTED', 'FULFILLED', 'DISMISSED'));

CREATE INDEX idx_procurement_reallocation
    ON procurement_suggestions (organization_id, status, warehouse_id, material_id, created_at)
    WHERE status = 'CONVERTED';
