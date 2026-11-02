package com.secureflow.secureflow.detection.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveAlertRequest(
        @NotBlank(message = "resolutionNote가 필요합니다.")
        @Size(max = 1000, message = "resolutionNote는 1000자 이하여야 합니다.")
        String resolutionNote
) {
}
