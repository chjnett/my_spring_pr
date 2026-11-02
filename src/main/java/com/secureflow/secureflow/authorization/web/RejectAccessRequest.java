package com.secureflow.secureflow.authorization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectAccessRequest(@NotBlank @Size(max = 500) String reason) {
}
