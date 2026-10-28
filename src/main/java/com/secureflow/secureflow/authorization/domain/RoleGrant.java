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

@Entity
@Table(
        name = "role_grants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_role_grant_user_role",
                columnNames = {"user_id", "role"}
        )
)
public class RoleGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // user 모듈의 Entity를 참조하지 않고 ID만 보관한다.
    // 추후 authorization 서비스를 분리해도 이 데이터 경계는 유지된다.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    protected RoleGrant() {
    }

    public RoleGrant(Long userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }
}
