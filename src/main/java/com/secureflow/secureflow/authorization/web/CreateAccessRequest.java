package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateAccessRequest(
        @NotNull @Positive Long targetUserId,
        @NotNull Role role,
        @NotBlank @Size(max = 500) String reason
) {
}
