package com.materialflow.erp.billing;

import com.materialflow.erp.security.ActorContextFactory;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/invoices")
public class BillingController {
    private final BillingApplicationService service;
    private final ActorContextFactory actors;

    public BillingController(BillingApplicationService service, ActorContextFactory actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.createInvoice(orderId, idempotencyKey, context);
    }
}

