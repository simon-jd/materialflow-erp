package com.materialflow.erp.billing;

import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/files")
public class PaymentController {
    private final ReconciliationApplicationService service;
    private final ActorContextFactory actors;

    public PaymentController(ReconciliationApplicationService service, ActorContextFactory actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentImportResponse importFile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody PaymentFileRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.importFile(request, idempotencyKey, context);
    }
}

