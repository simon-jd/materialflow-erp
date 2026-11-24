package com.materialflow.erp.inventory;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.identity.ActorContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point-in-time stock positions for operator screens. Read-only and lock-free: it never touches the
 * allocation hot path, and the authoritative availability check still happens when an order reserves.
 */
@Service
public class InventoryAvailabilityQueryApplicationService {
    private final JdbcTemplate jdbc;

    public InventoryAvailabilityQueryApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<InventoryAvailabilityResponse> list(
            UUID warehouseId, UUID materialId, int page, int size, ActorContext actor) {
        if (warehouseId != null) {
            actor.requireWarehouse(warehouseId);
        }
        StringBuilder where = new StringBuilder(" WHERE b.organization_id = ?");
        List<Object> arguments = new ArrayList<>(List.of(actor.organizationId()));
        if (!actor.warehouseScope().isEmpty()) {
            where.append(" AND b.warehouse_id IN (")
                    .append(String.join(", ", Collections.nCopies(actor.warehouseScope().size(), "?")))
                    .append(')');
            arguments.addAll(actor.warehouseScope());
        }
        if (warehouseId != null) {
            where.append(" AND b.warehouse_id = ?");
            arguments.add(warehouseId);
        }
        if (materialId != null) {
            where.append(" AND b.material_id = ?");
            arguments.add(materialId);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM inventory_balances b" + where, Long.class,
                arguments.toArray());
        arguments.add(size);
        arguments.add(PageResponse.offset(page, size));
        List<InventoryAvailabilityResponse> items = jdbc.query("""
                SELECT b.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name, b.material_id, m.sku,
                       m.name AS material_name, m.base_unit, b.location_id, b.on_hand, b.reserved, b.available,
                       b.in_transit, b.updated_at
                  FROM inventory_balances b
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.organization_id = b.organization_id
                  JOIN materials m ON m.id = b.material_id AND m.organization_id = b.organization_id
                """ + where + " ORDER BY w.code, m.sku, b.location_id LIMIT ? OFFSET ?",
                (resultSet, row) -> new InventoryAvailabilityResponse(
                        resultSet.getObject("warehouse_id", UUID.class), resultSet.getString("warehouse_code"),
                        resultSet.getString("warehouse_name"), resultSet.getObject("material_id", UUID.class),
                        resultSet.getString("sku"), resultSet.getString("material_name"),
                        resultSet.getString("base_unit"), resultSet.getObject("location_id", UUID.class),
                        resultSet.getBigDecimal("on_hand"), resultSet.getBigDecimal("reserved"),
                        resultSet.getBigDecimal("available"), resultSet.getBigDecimal("in_transit"),
                        resultSet.getObject("updated_at", OffsetDateTime.class)),
                arguments.toArray());
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }
}
