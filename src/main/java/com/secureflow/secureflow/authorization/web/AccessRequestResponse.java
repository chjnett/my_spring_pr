package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.AccessRequestResult;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;

import java.time.Instant;

public record AccessRequestResponse(
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
    public static AccessRequestResponse from(AccessRequestResult result) {
        return new AccessRequestResponse(
                result.id(), result.requesterId(), result.targetUserId(), result.requestedRole(),
                result.reason(), result.status(), result.reviewerId(), result.decisionReason(),
                result.reviewedAt(), result.version(), result.createdAt(), result.updatedAt()
        );
    }
}
