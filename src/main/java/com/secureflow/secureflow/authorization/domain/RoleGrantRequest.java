package com.secureflow.secureflow.authorization.domain;

import com.secureflow.secureflow.global.exception.AccessRequestAlreadyDecidedException;
import com.secureflow.secureflow.global.exception.SelfApprovalNotAllowedException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "role_grant_requests")
public class RoleGrantRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_role", nullable = false, length = 30)
    private Role requestedRole;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleGrantRequestStatus status;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoleGrantRequest() {
    }

    public RoleGrantRequest(Long requesterId, Long targetUserId, Role requestedRole, String reason) {
        this.requesterId = requesterId;
        this.targetUserId = targetUserId;
        this.requestedRole = requestedRole;
        this.reason = reason;
        this.status = RoleGrantRequestStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void approve(Long reviewerId, String decisionReason) {
        validateReview(reviewerId);
        this.status = RoleGrantRequestStatus.APPROVED;
        finishReview(reviewerId, decisionReason);
    }

    public void reject(Long reviewerId, String decisionReason) {
        validateReview(reviewerId);
        this.status = RoleGrantRequestStatus.REJECTED;
        finishReview(reviewerId, decisionReason);
    }

    private void validateReview(Long reviewerId) {
        if (status != RoleGrantRequestStatus.PENDING) {
            throw new AccessRequestAlreadyDecidedException(id, status);
        }
        if (requesterId.equals(reviewerId)) {
            throw new SelfApprovalNotAllowedException(id, reviewerId);
        }
    }

    private void finishReview(Long reviewerId, String decisionReason) {
        this.reviewerId = reviewerId;
        this.decisionReason = decisionReason;
        this.reviewedAt = Instant.now();
        this.updatedAt = reviewedAt;
    }

    public Long getId() { return id; }
    public Long getRequesterId() { return requesterId; }
    public Long getTargetUserId() { return targetUserId; }
    public Role getRequestedRole() { return requestedRole; }
    public String getReason() { return reason; }
    public RoleGrantRequestStatus getStatus() { return status; }
    public Long getReviewerId() { return reviewerId; }
    public String getDecisionReason() { return decisionReason; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
