package com.materialflow.erp.approval;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A sales-order approval as seen by the calling actor. {@code decidableByActor} is a UI hint only;
 * the decision endpoint re-checks role, frozen approver set and maker-checker on every call.
 */
public record ApprovalSummaryResponse(
        UUID approvalId,
        UUID orderId,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        UUID applicantId,
        String status,
        String mode,
        int requiredApprovals,
        int approvedCount,
        OffsetDateTime createdAt,
        long version,
        boolean decidableByActor) {
}
