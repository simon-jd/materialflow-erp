package com.materialflow.erp.fulfillment;

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
@RequestMapping("/api/v1/orders/{orderId}/shipments")
public class FulfillmentController {
    private final FulfillmentApplicationService service;
    private final ActorContextFactory actors;

    public FulfillmentController(FulfillmentApplicationService service, ActorContextFactory actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentResponse ship(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouseScope,
            Authentication authentication,
            @Valid @RequestBody CreateShipmentRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouseScope);
        return service.ship(orderId, request, idempotencyKey, context);
    }
}

