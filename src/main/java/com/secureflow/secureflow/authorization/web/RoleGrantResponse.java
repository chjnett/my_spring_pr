package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.GrantedRole;
import com.secureflow.secureflow.authorization.domain.Role;

public record RoleGrantResponse(Long id, Long userId, Role role) {
    public static RoleGrantResponse from(GrantedRole grantedRole) {
        return new RoleGrantResponse(
                grantedRole.id(),
                grantedRole.userId(),
                grantedRole.role()
        );
    }
}
