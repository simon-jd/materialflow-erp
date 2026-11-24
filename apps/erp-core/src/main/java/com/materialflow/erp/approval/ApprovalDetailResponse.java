package com.materialflow.erp.approval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ApprovalDetailResponse(ApprovalSummaryResponse approval, List<Decision> decisions) {
    public ApprovalDetailResponse {
        decisions = List.copyOf(decisions);
    }

    public record Decision(UUID approverId, String decision, String reason, OffsetDateTime decidedAt) {
    }
}
