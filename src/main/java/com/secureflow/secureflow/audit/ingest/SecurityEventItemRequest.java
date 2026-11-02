package com.secureflow.secureflow.audit.ingest;

import java.time.Instant;
import java.util.Map;

public record SecurityEventItemRequest(
        String eventId,
        String eventType,
        Long actorId,
        String sourceIp,
        String outcome,
        Instant occurredAt,
        Map<String, String> attributes
) {
}
