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
}
