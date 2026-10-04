package com.materialflow.erp.procurement;

import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement")
public class ProcurementController {
    private final ProcurementApplicationService service;
    private final ActorContextFactory actors;

    public ProcurementController(ProcurementApplicationService service, ActorContextFactory actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/purchase-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseRequestResponse create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody CreatePurchaseRequest request) {
        return service.create(request, idempotencyKey,
                actors.create(authentication, organization, actor, roles, warehouses));
    }

    @PostMapping("/purchase-requests/{requestId}/approve")
    public PurchaseRequestResponse approve(
            @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        return service.approve(requestId, idempotencyKey,
                actors.create(authentication, organization, actor, roles, warehouses));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseReceiptResponse receive(
            @PathVariable UUID purchaseOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody ReceivePurchaseOrderRequest request) {
        return service.receive(purchaseOrderId, request, idempotencyKey,
                actors.create(authentication, organization, actor, roles, warehouses));
    }
}

