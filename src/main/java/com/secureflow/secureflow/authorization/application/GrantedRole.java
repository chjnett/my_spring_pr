package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.Role;

public record GrantedRole(Long id, Long userId, Role role) {
}
