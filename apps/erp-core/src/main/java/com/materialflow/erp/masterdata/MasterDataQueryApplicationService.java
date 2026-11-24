package com.materialflow.erp.masterdata;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.identity.ActorContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Active, tenant-scoped reference data for pickers and display names. */
@Service
public class MasterDataQueryApplicationService {
    private final JdbcTemplate jdbc;

    public MasterDataQueryApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<CustomerSummaryResponse> customers(String search, int page, int size, ActorContext actor) {
        Query query = new Query("customers", actor.organizationId()).search(search, "code", "name");
        return query.page("id, code, name, region_code", "code, id", page, size,
                (resultSet, row) -> new CustomerSummaryResponse(resultSet.getObject("id", UUID.class),
                        resultSet.getString("code"), resultSet.getString("name"), resultSet.getString("region_code")));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MaterialSummaryResponse> materials(String search, int page, int size, ActorContext actor) {
        Query query = new Query("materials", actor.organizationId()).search(search, "sku", "name");
        return query.page("id, sku, name, specification, base_unit", "sku, id", page, size,
                (resultSet, row) -> new MaterialSummaryResponse(resultSet.getObject("id", UUID.class),
                        resultSet.getString("sku"), resultSet.getString("name"), resultSet.getString("specification"),
                        resultSet.getString("base_unit")));
    }

    /** Warehouses outside a non-empty actor warehouse scope are not listed. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<WarehouseSummaryResponse> warehouses(int page, int size, ActorContext actor) {
        Query query = new Query("warehouses", actor.organizationId()).idIn(new ArrayList<>(actor.warehouseScope()));
        return query.page("id, code, name, region_code", "code, id", page, size,
                (resultSet, row) -> new WarehouseSummaryResponse(resultSet.getObject("id", UUID.class),
                        resultSet.getString("code"), resultSet.getString("name"), resultSet.getString("region_code")));
    }

    /** Table and column names are compile-time constants from this class; only values are bound. */
    private final class Query {
        private final String table;
        private final StringBuilder where = new StringBuilder(" WHERE organization_id = ? AND active");
        private final List<Object> arguments = new ArrayList<>();

        private Query(String table, UUID organizationId) {
            this.table = table;
            arguments.add(organizationId);
        }

        private Query search(String text, String codeColumn, String nameColumn) {
            if (text != null && !text.isBlank()) {
                String pattern = "%" + text.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                        + "%";
                where.append(" AND (").append(codeColumn).append(" ILIKE ? OR ").append(nameColumn)
                        .append(" ILIKE ?)");
                arguments.add(pattern);
                arguments.add(pattern);
            }
            return this;
        }

        private Query idIn(List<UUID> ids) {
            if (!ids.isEmpty()) {
                where.append(" AND id IN (").append(String.join(", ", Collections.nCopies(ids.size(), "?")))
                        .append(')');
                arguments.addAll(ids);
            }
            return this;
        }

        private <T> PageResponse<T> page(String columns, String orderBy, int page, int size, RowMapper<T> mapper) {
            Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class,
                    arguments.toArray());
            List<Object> paged = new ArrayList<>(arguments);
            paged.add(size);
            paged.add(PageResponse.offset(page, size));
            List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + " ORDER BY " + orderBy
                    + " LIMIT ? OFFSET ?", mapper, paged.toArray());
            return PageResponse.of(items, page, size, total == null ? 0 : total);
        }
    }
}
