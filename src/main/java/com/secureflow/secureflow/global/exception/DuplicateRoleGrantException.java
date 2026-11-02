package com.secureflow.secureflow.global.exception;

import com.secureflow.secureflow.authorization.domain.Role;

public class DuplicateRoleGrantException extends RuntimeException {
    public DuplicateRoleGrantException(Long userId, Role role) {
        super("이미 부여된 권한입니다. userId=" + userId + ", role=" + role);
    }

    public DuplicateRoleGrantException(Long userId, Role role, Throwable cause) {
        super("이미 부여된 권한입니다. userId=" + userId + ", role=" + role, cause);
    }
}
