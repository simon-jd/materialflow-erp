package com.materialflow.erp.masterdata;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MasterDataController {
    private final MasterDataQueryApplicationService queries;
    private final ActorContextFactory actors;

    public MasterDataController(MasterDataQueryApplicationService queries, ActorContextFactory actors) {
        this.queries = queries;
        this.actors = actors;
    }

    @GetMapping("/customers")
    public PageResponse<CustomerSummaryResponse> customers(
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.customers(q, page, size, context);
    }

    @GetMapping("/materials")
    public PageResponse<MaterialSummaryResponse> materials(
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.materials(q, page, size, context);
    }

    @GetMapping("/warehouses")
    public PageResponse<WarehouseSummaryResponse> warehouses(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.warehouses(page, size, context);
    }
}
