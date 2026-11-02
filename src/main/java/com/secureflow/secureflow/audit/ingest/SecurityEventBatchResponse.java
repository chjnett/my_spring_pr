package com.secureflow.secureflow.audit.ingest;

import java.util.List;

public record SecurityEventBatchResponse(
        int accepted,
        int duplicate,
        int rejected,
        List<SecurityEventItemResult> results
) {
}
