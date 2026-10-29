package com.secureflow.secureflow.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 한 호출 주체가 같은 API 작업을 재시도했는지 식별한다.
 *
 * RoleGrant의 UNIQUE 제약은 비즈니스 중복을 막고, 이 테이블의 UNIQUE 제약은
 * 같은 네트워크 작업이 두 번 실행되는 것을 막는다.
 */
@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_user_scope_key",
                columnNames = {"user_id", "request_scope", "idempotency_key"}
        )
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_scope", nullable = false, length = 100)
    private String requestScope;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_grant_id")
    private Long responseGrantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_role", length = 30)
    private Role responseRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String requestScope, String idempotencyKey, String requestHash) {
        this.userId = userId;
        this.requestScope = requestScope;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.PROCESSING;
        this.createdAt = Instant.now();
    }

    public void complete(RoleGrant roleGrant) {
        this.responseGrantId = roleGrant.getId();
        this.responseRole = roleGrant.getRole();
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean hasSameRequest(String hash) {
        return requestHash.equals(hash);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public GrantedResponse completedResponse() {
        if (!isCompleted() || responseGrantId == null || responseRole == null) {
            throw new IllegalStateException("완료되지 않은 멱등성 요청에는 재사용할 응답이 없습니다.");
        }
        return new GrantedResponse(responseGrantId, userId, responseRole);
    }

    public record GrantedResponse(Long grantId, Long userId, Role role) {
    }
}
