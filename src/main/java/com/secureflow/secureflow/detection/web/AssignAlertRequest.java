package com.secureflow.secureflow.detection.web;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;

public record AssignAlertRequest(
        @NotNull(message = "assigneeId가 필요합니다.")
        @Positive(message = "assigneeId는 양수여야 합니다.") Long assigneeId
) {
}
