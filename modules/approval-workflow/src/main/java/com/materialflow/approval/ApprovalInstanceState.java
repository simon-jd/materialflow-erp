package com.materialflow.approval;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ApprovalInstanceState {
    private final UUID applicantId;
    private final long templateVersion;
    private final ApprovalMode mode;
    private final int requiredApprovals;
    private final Set<UUID> approvers;
    private final Set<UUID> approvedBy;
    private final Set<UUID> rejectedBy;
    private ApprovalStatus status;

    public ApprovalInstanceState(
            UUID applicantId,
            long templateVersion,
            ApprovalMode mode,
            int requiredApprovals,
            Set<UUID> resolvedApprovers) {
        if (applicantId == null || templateVersion < 1 || mode == null || resolvedApprovers == null
                || resolvedApprovers.isEmpty()) {
            throw new IllegalArgumentException("Approval snapshot is incomplete");
        }
        this.applicantId = applicantId;
        this.templateVersion = templateVersion;
        this.mode = mode;
        this.approvers = new HashSet<>(resolvedApprovers);
        this.requiredApprovals = switch (mode) {
            case ANY -> 1;
            case ALL -> approvers.size();
            case N_OF_M -> requiredApprovals;
        };
        if (this.requiredApprovals < 1 || this.requiredApprovals > approvers.size()) {
            throw new IllegalArgumentException("Invalid approval threshold");
        }
        this.approvedBy = new HashSet<>();
        this.rejectedBy = new HashSet<>();
        this.status = ApprovalStatus.PENDING;
    }

    public synchronized ApprovalStatus decide(UUID actorId, ApprovalDecision decision) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval instance is already terminal");
        }
        if (applicantId.equals(actorId)) {
            throw new MakerCheckerViolationException();
        }
        if (!approvers.contains(actorId)) {
            throw new IllegalArgumentException("Actor is not a resolved approver");
        }
        if (approvedBy.contains(actorId) || rejectedBy.contains(actorId)) {
            throw new DuplicateDecisionException();
        }
        if (decision == ApprovalDecision.REJECT) {
            rejectedBy.add(actorId);
            status = ApprovalStatus.REJECTED;
        } else {
            approvedBy.add(actorId);
            if (approvedBy.size() >= requiredApprovals) {
                status = ApprovalStatus.APPROVED;
            }
        }
        return status;
    }

    public synchronized void delegate(UUID from, UUID to, UUID actorId) {
        if (status != ApprovalStatus.PENDING || !actorId.equals(from) || !approvers.contains(from)
                || to == null || to.equals(applicantId)) {
            throw new IllegalArgumentException("Invalid delegation");
        }
        if (approvedBy.contains(from) || rejectedBy.contains(from) || approvers.contains(to)) {
            throw new IllegalArgumentException("Invalid delegation");
        }
        approvers.remove(from);
        approvers.add(to);
    }

    public UUID applicantId() { return applicantId; }
    public long templateVersion() { return templateVersion; }
    public ApprovalStatus status() { return status; }
    public Set<UUID> approvers() { return Set.copyOf(approvers); }
    public Set<UUID> approvedBy() { return Set.copyOf(approvedBy); }

    public static final class MakerCheckerViolationException extends RuntimeException {
        public MakerCheckerViolationException() { super("Applicant cannot approve their own request"); }
    }

    public static final class DuplicateDecisionException extends RuntimeException {
        public DuplicateDecisionException() { super("Approver has already decided"); }
    }
}
