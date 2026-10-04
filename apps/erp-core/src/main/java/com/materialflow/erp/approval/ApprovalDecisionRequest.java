package com.materialflow.erp.approval;

import com.materialflow.approval.ApprovalDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @NotNull ApprovalDecision decision,
        @Size(max = 500) String reason) {
}

