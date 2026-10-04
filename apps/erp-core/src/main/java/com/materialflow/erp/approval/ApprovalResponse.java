package com.materialflow.erp.approval;

import java.util.UUID;

public record ApprovalResponse(UUID approvalId, UUID objectId, String status, int approvedCount, long version) {
}

