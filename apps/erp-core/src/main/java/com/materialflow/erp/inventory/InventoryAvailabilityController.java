package com.materialflow.erp.inventory;

import com.materialflow.erp.common.PageResponse;
import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryAvailabilityController {
    private final InventoryAvailabilityQueryApplicationService queries;
    private final ActorContextFactory actors;

    public InventoryAvailabilityController(InventoryAvailabilityQueryApplicationService queries,
            ActorContextFactory actors) {
        this.queries = queries;
        this.actors = actors;
    }

    @GetMapping("/availability")
    public PageResponse<InventoryAvailabilityResponse> availability(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.list(warehouseId, materialId, page, size, context);
    }
}
