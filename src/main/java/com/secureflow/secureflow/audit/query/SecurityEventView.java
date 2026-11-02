package com.secureflow.secureflow.audit.query;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record SecurityEventView(
        long id,
        String eventId,
        String eventType,
        Long actorId,
        String source,
        String sourceIp,
        String outcome,
        Instant occurredAt,
        Instant ingestedAt,
        String correlationId,
        JsonNode payload
) {
}
