package com.secureflow.secureflow.authorization.domain;

import com.secureflow.secureflow.global.exception.AccessRequestAlreadyDecidedException;
import com.secureflow.secureflow.global.exception.SelfApprovalNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleGrantRequestTest {

    @Test
    void approvesPendingRequestByDifferentReviewer() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.ADMIN, "운영 권한 필요");

        request.approve(3L, "업무 확인");

        assertThat(request.getStatus()).isEqualTo(RoleGrantRequestStatus.APPROVED);
        assertThat(request.getReviewerId()).isEqualTo(3L);
        assertThat(request.getReviewedAt()).isNotNull();
    }

    @Test
    void rejectsPendingRequest() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.AUDITOR, "로그 검토");

        request.reject(3L, "최소 권한 원칙 위반");

        assertThat(request.getStatus()).isEqualTo(RoleGrantRequestStatus.REJECTED);
        assertThat(request.getDecisionReason()).isEqualTo("최소 권한 원칙 위반");
    }

    @Test
    void requesterCannotReviewOwnRequest() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.ADMIN, "운영 권한 필요");

        assertThatThrownBy(() -> request.approve(1L, null))
                .isInstanceOf(SelfApprovalNotAllowedException.class);
    }

    @Test
    void decidedRequestCannotBeReviewedAgain() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.ADMIN, "운영 권한 필요");
        request.approve(3L, null);

        assertThatThrownBy(() -> request.reject(4L, "재검토"))
                .isInstanceOf(AccessRequestAlreadyDecidedException.class);
    }
}
