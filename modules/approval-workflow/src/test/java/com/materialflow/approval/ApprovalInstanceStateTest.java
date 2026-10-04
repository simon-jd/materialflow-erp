package com.materialflow.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalInstanceStateTest {
    @Test
    void freezesTemplateVersionAndRequiresNDistinctApprovers() {
        UUID applicant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var state = new ApprovalInstanceState(applicant, 4, ApprovalMode.N_OF_M, 2, Set.of(first, second));

        assertThat(state.decide(first, ApprovalDecision.APPROVE)).isEqualTo(ApprovalStatus.PENDING);
        assertThat(state.decide(second, ApprovalDecision.APPROVE)).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(state.templateVersion()).isEqualTo(4);
    }

    @Test
    void enforcesMakerChecker() {
        UUID applicant = UUID.randomUUID();
        var state = new ApprovalInstanceState(applicant, 1, ApprovalMode.ANY, 1, Set.of(applicant));
        assertThatThrownBy(() -> state.decide(applicant, ApprovalDecision.APPROVE))
                .isInstanceOf(ApprovalInstanceState.MakerCheckerViolationException.class);
    }

    @Test
    void delegatesPendingAssignmentWithoutChangingTheTemplateVersion() {
        UUID applicant = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        UUID delegate = UUID.randomUUID();
        var state = new ApprovalInstanceState(applicant, 7, ApprovalMode.ANY, 1, Set.of(original));

        state.delegate(original, delegate, original);

        assertThat(state.templateVersion()).isEqualTo(7);
        assertThat(state.approvers()).containsExactly(delegate);
        assertThat(state.decide(delegate, ApprovalDecision.APPROVE)).isEqualTo(ApprovalStatus.APPROVED);
    }
}
