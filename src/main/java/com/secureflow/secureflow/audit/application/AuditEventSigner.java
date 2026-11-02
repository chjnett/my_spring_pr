package com.secureflow.secureflow.audit.application;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuditEventSigner {
    private static final String ALGORITHM = "HmacSHA256";
    public static final int LEGACY_VERSION = 1;
    public static final int CORRELATION_VERSION = 2;
    public static final int SEARCH_FIELDS_VERSION = 3;

    private final String activeKeyId;
    private final Map<String, byte[]> verificationKeys = new LinkedHashMap<>();

    public AuditEventSigner(AuditIntegrityProperties properties) {
        if (properties.getKeyId() == null || properties.getKeyId().isBlank()
                || properties.getSecret() == null || properties.getSecret().length() < 32) {
            throw new IllegalStateException("감사 이벤트 HMAC 활성 key-id와 32자 이상의 secret이 필요합니다.");
        }
        activeKeyId = properties.getKeyId();
        properties.getVerificationKeys().forEach(this::addKey);
        addKey(activeKeyId, properties.getSecret());
    }

    public SignedAuditEvent sign(String eventId, String aggregateType, String aggregateId, String eventType,
                                 Long actorId, String payload, Instant occurredAt) {
        return signed(LEGACY_VERSION, canonical(eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt));
    }

    public SignedAuditEvent signWithCorrelation(String eventId, String aggregateType, String aggregateId, String eventType,
                                                Long actorId, String payload, Instant occurredAt, String correlationId) {
        return signed(CORRELATION_VERSION, canonical(eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt, correlationId));
    }

    public SignedAuditEvent signWithSearchFields(String eventId, String aggregateType, String aggregateId, String eventType,
                                                 Long actorId, String payload, Instant occurredAt, String correlationId,
                                                 String source, String sourceIp, String outcome) {
        return signed(SEARCH_FIELDS_VERSION, canonical(eventId, aggregateType, aggregateId, eventType, actorId,
                payload, occurredAt, correlationId, source, sourceIp, outcome));
    }

    public boolean supports(String keyId) { return verificationKeys.containsKey(keyId); }

    public boolean verify(String keyId, String signature, String eventId, String aggregateType, String aggregateId,
                          String eventType, Long actorId, String payload, Instant occurredAt) {
        return matches(keyId, signature, canonical(eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt));
    }

    public boolean verifyWithCorrelation(String keyId, String signature, String eventId, String aggregateType,
                                         String aggregateId, String eventType, Long actorId, String payload,
                                         Instant occurredAt, String correlationId) {
        return matches(keyId, signature, canonical(eventId, aggregateType, aggregateId, eventType, actorId,
                payload, occurredAt, correlationId));
    }

    public boolean verifyWithSearchFields(String keyId, String signature, String eventId, String aggregateType,
                                          String aggregateId, String eventType, Long actorId, String payload,
                                          Instant occurredAt, String correlationId, String source, String sourceIp,
                                          String outcome) {
        return matches(keyId, signature, canonical(eventId, aggregateType, aggregateId, eventType, actorId,
                payload, occurredAt, correlationId, source, sourceIp, outcome));
    }

    private SignedAuditEvent signed(int version, String canonical) {
        return new SignedAuditEvent(activeKeyId, sign(activeKeyId, canonical), version);
    }

    private boolean matches(String keyId, String signature, String canonical) {
        return signature != null && supports(keyId) && java.security.MessageDigest.isEqual(
                sign(keyId, canonical).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String sign(String keyId, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(verificationKeys.get(keyId), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("감사 이벤트 HMAC을 초기화할 수 없습니다.", exception);
        }
    }

    private void addKey(String keyId, String secret) {
        if (keyId == null || keyId.isBlank() || secret == null || secret.length() < 32) {
            throw new IllegalStateException("모든 감사 이벤트 HMAC key는 key-id와 32자 이상의 secret이 필요합니다.");
        }
        verificationKeys.put(keyId, secret.getBytes(StandardCharsets.UTF_8));
    }

    private String canonical(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.getBytes(StandardCharsets.UTF_8).length).append(':').append(text);
        }
        return result.toString();
    }

    public record SignedAuditEvent(String keyId, String signature, int version) { }
}
