package com.secureflow.secureflow.authorization.web;

import jakarta.validation.constraints.Size;

public record ReviewAccessRequest(@Size(max = 500) String reason) {
}
