package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;

import java.time.Instant;

public record AccessRequestResult(
        Long id,
        Long requesterId,
        Long targetUserId,
        Role requestedRole,
        String reason,
        RoleGrantRequestStatus status,
        Long reviewerId,
        String decisionReason,
        Instant reviewedAt,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccessRequestResult from(RoleGrantRequest request) {
        return new AccessRequestResult(
                request.getId(), request.getRequesterId(), request.getTargetUserId(),
                request.getRequestedRole(), request.getReason(), request.getStatus(),
                request.getReviewerId(), request.getDecisionReason(), request.getReviewedAt(),
                request.getVersion(), request.getCreatedAt(), request.getUpdatedAt()
        );
    }

    /** 요청이 나중에 승인·거절되어도 최초 생성 시점의 201 응답을 재구성한다. */
    public static AccessRequestResult createdSnapshot(RoleGrantRequest request) {
        return new AccessRequestResult(
                request.getId(), request.getRequesterId(), request.getTargetUserId(),
                request.getRequestedRole(), request.getReason(), RoleGrantRequestStatus.PENDING,
                null, null, null, 0L, request.getCreatedAt(), request.getCreatedAt()
        );
    }
}
