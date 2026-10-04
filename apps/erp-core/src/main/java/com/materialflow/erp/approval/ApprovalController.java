package com.materialflow.erp.approval;

import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalApplicationService service;
    private final ActorContextFactory actors;

    public ApprovalController(ApprovalApplicationService service, ActorContextFactory actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/{approvalId}/decisions")
    public ApprovalResponse decide(
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Id") String organization,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.decide(approvalId, request, idempotencyKey, context);
    }
}

