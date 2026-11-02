package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.Role;

public record CreateAccessRequestCommand(Long targetUserId, Role role, String reason) {
}
