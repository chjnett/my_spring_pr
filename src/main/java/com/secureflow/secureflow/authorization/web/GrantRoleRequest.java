package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.domain.Role;
import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(@NotNull Role role) {
}
