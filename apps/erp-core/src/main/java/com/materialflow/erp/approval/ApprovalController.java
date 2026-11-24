package com.materialflow.erp.approval;

import com.materialflow.approval.ApprovalStatus;
import com.materialflow.erp.common.PageResponse;
import com.materialflow.erp.security.ActorContextFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalApplicationService service;
    private final ApprovalQueryApplicationService queries;
    private final ActorContextFactory actors;

    public ApprovalController(ApprovalApplicationService service, ApprovalQueryApplicationService queries,
            ActorContextFactory actors) {
        this.service = service;
        this.queries = queries;
        this.actors = actors;
    }

    @GetMapping
    public PageResponse<ApprovalSummaryResponse> list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.list(status, page, size, context);
    }

    @GetMapping("/{approvalId}")
    public ApprovalDetailResponse get(
            @PathVariable UUID approvalId,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return queries.get(approvalId, context);
    }

    @PostMapping("/{approvalId}/decisions")
    public ApprovalResponse decide(
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Organization-Id", required = false) String organization,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestHeader(value = "X-Roles", required = false) String roles,
            @RequestHeader(value = "X-Warehouse-Scope", required = false) String warehouses,
            Authentication authentication,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        var context = actors.create(authentication, organization, actor, roles, warehouses);
        return service.decide(approvalId, request, idempotencyKey, context);
    }
}
