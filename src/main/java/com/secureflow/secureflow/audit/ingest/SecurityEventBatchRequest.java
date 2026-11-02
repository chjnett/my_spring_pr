package com.secureflow.secureflow.audit.ingest;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SecurityEventBatchRequest(
        @NotEmpty(message = "events는 한 건 이상이어야 합니다.")
        @Size(max = 500, message = "한 배치는 최대 500건입니다.")
        List<SecurityEventItemRequest> events
) {
}
