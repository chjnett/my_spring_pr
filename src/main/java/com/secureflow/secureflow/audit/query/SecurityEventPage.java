package com.secureflow.secureflow.audit.query;

import java.time.Instant;
import java.util.List;

public record SecurityEventPage(
        Instant from,
        Instant to,
        int size,
        String nextCursor,
        List<SecurityEventView> events
) {
}
