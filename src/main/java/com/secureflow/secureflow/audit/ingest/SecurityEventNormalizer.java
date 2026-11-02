package com.secureflow.secureflow.audit.ingest;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class SecurityEventNormalizer {

    private static final Set<String> ALLOWED_ATTRIBUTE_KEYS = Set.of(
            "action", "reasonCode", "resource", "result", "userAgent"
    );
    private static final Duration MAX_PAST_AGE = Duration.ofDays(31);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;

    public SecurityEventNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizationResult normalize(SecurityEventItemRequest request, Instant now) {
        if (request == null) {
            return NormalizationResult.rejected(null, "EVENT_REQUIRED", "이벤트 항목이 필요합니다.");
        }
        String eventId = normalizeUuid(request.eventId());
        if (eventId == null) {
            return NormalizationResult.rejected(request.eventId(), "INVALID_EVENT_ID", "eventId는 UUID여야 합니다.");
        }
        ExternalSecurityEventType eventType = enumValue(ExternalSecurityEventType.class, request.eventType());
        if (eventType == null) {
            return NormalizationResult.rejected(eventId, "INVALID_EVENT_TYPE", "허용되지 않은 eventType입니다.");
        }
        if (request.actorId() == null || request.actorId() < 1) {
            return NormalizationResult.rejected(eventId, "INVALID_ACTOR_ID", "actorId는 양수여야 합니다.");
        }
        String sourceIp = normalizeIpAddress(request.sourceIp());
        if (sourceIp == null) {
            return NormalizationResult.rejected(eventId, "INVALID_SOURCE_IP", "sourceIp는 IPv4 또는 IPv6 주소여야 합니다.");
        }
        SecurityEventOutcome outcome = enumValue(SecurityEventOutcome.class, request.outcome());
        if (outcome == null) {
            return NormalizationResult.rejected(eventId, "INVALID_OUTCOME", "허용되지 않은 outcome입니다.");
        }
        if (request.occurredAt() == null
                || request.occurredAt().isBefore(now.minus(MAX_PAST_AGE))
                || request.occurredAt().isAfter(now.plus(MAX_FUTURE_SKEW))) {
            return NormalizationResult.rejected(
                    eventId, "INVALID_OCCURRED_AT", "occurredAt은 최근 31일에서 미래 5분 사이여야 합니다."
            );
        }
        Instant occurredAt = Instant.ofEpochSecond(
                request.occurredAt().getEpochSecond(),
                request.occurredAt().getNano() / 1_000 * 1_000L
        );
        Map<String, String> attributes = request.attributes() == null
                ? Map.of()
                : request.attributes();
        if (attributes.size() > 10) {
            return NormalizationResult.rejected(eventId, "TOO_MANY_ATTRIBUTES", "attributes는 최대 10개입니다.");
        }
        TreeMap<String, String> normalizedAttributes = new TreeMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!ALLOWED_ATTRIBUTE_KEYS.contains(entry.getKey())) {
                return NormalizationResult.rejected(
                        eventId, "ATTRIBUTE_NOT_ALLOWED", "허용되지 않은 attributes 키입니다: " + entry.getKey()
                );
            }
            if (entry.getValue() == null || entry.getValue().length() > 256) {
                return NormalizationResult.rejected(
                        eventId, "INVALID_ATTRIBUTE_VALUE", "attributes 값은 256자 이하여야 합니다."
                );
            }
            normalizedAttributes.put(entry.getKey(), entry.getValue());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceIp", sourceIp);
        payload.put("outcome", outcome.name());
        payload.put("attributes", normalizedAttributes);
        try {
            return NormalizationResult.accepted(new NormalizedSecurityEvent(
                    eventId,
                    eventType.name(),
                    request.actorId(),
                    sourceIp,
                    outcome.name(),
                    occurredAt,
                    objectMapper.writeValueAsString(payload)
            ));
        } catch (JacksonException exception) {
            throw new IllegalStateException("보안 이벤트 payload를 직렬화할 수 없습니다.", exception);
        }
    }

    private String normalizeUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public String normalizeIpAddress(String value) {
        if (value == null || value.isBlank() || value.contains("%")) {
            return null;
        }
        if (value.contains(":")) {
            try {
                return InetAddress.getByName(value).getHostAddress();
            } catch (UnknownHostException exception) {
                return null;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < octets.length; index++) {
            if (!octets[index].matches("[0-9]{1,3}")) {
                return null;
            }
            int octet = Integer.parseInt(octets[index]);
            if (octet > 255) {
                return null;
            }
            if (index > 0) {
                normalized.append('.');
            }
            normalized.append(octet);
        }
        return normalized.toString();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return value == null ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record NormalizedSecurityEvent(
            String eventId,
            String eventType,
            Long actorId,
            String sourceIp,
            String outcome,
            Instant occurredAt,
            String payload
    ) {
    }

    public record NormalizationResult(
            NormalizedSecurityEvent event,
            String eventId,
            String errorCode,
            String message
    ) {
        static NormalizationResult accepted(NormalizedSecurityEvent event) {
            return new NormalizationResult(event, event.eventId(), null, null);
        }

        static NormalizationResult rejected(String eventId, String errorCode, String message) {
            return new NormalizationResult(null, eventId, errorCode, message);
        }

        public boolean accepted() {
            return event != null;
        }
    }
}
