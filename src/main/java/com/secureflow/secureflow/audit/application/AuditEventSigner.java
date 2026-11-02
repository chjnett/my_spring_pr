package com.secureflow.secureflow.audit.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class AuditEventSigner {

    private static final String ALGORITHM = "HmacSHA256";
    public static final int LEGACY_VERSION = 1;
    public static final int CORRELATION_VERSION = 2;

    private final String keyId;
    private final byte[] secret;

    public AuditEventSigner(
            @Value("${secureflow.audit.integrity.key-id}") String keyId,
            @Value("${secureflow.audit.integrity.secret}") String secret
    ) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("감사 이벤트 HMAC key-id가 필요합니다.");
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("감사 이벤트 HMAC secret은 32자 이상이어야 합니다.");
        }
        this.keyId = keyId;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public SignedAuditEvent sign(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, Instant occurredAt
    ) {
        byte[] signature = newMac().doFinal(canonicalBytes(
                eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt
        ));
        return new SignedAuditEvent(keyId, HexFormat.of().formatHex(signature), LEGACY_VERSION);
    }

    public SignedAuditEvent signWithCorrelation(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, Instant occurredAt, String correlationId
    ) {
        byte[] signature = newMac().doFinal(canonicalBytesWithCorrelation(
                eventId, aggregateType, aggregateId, eventType, actorId, payload,
                occurredAt, correlationId
        ));
        return new SignedAuditEvent(
                keyId, HexFormat.of().formatHex(signature), CORRELATION_VERSION
        );
    }

    public boolean verify(
            String signatureKeyId, String expectedSignature, String eventId,
            String aggregateType, String aggregateId, String eventType, Long actorId,
            String payload, Instant occurredAt
    ) {
        if (!keyId.equals(signatureKeyId)) {
            return false;
        }
        SignedAuditEvent recalculated = sign(
                eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt
        );
        return java.security.MessageDigest.isEqual(
                recalculated.signature().getBytes(StandardCharsets.US_ASCII),
                expectedSignature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    public boolean supports(String signatureKeyId) {
        return keyId.equals(signatureKeyId);
    }

    public boolean verifyWithCorrelation(
            String signatureKeyId, String expectedSignature, String eventId,
            String aggregateType, String aggregateId, String eventType, Long actorId,
            String payload, Instant occurredAt, String correlationId
    ) {
        if (!keyId.equals(signatureKeyId)) {
            return false;
        }
        SignedAuditEvent recalculated = signWithCorrelation(
                eventId, aggregateType, aggregateId, eventType, actorId, payload,
                occurredAt, correlationId
        );
        return java.security.MessageDigest.isEqual(
                recalculated.signature().getBytes(StandardCharsets.US_ASCII),
                expectedSignature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac;
        } catch (Exception exception) {
            throw new IllegalStateException("감사 이벤트 HMAC을 초기화할 수 없습니다.", exception);
        }
    }

    private byte[] canonicalBytes(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, Instant occurredAt
    ) {
        return canonicalValue(
                eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt
        ).getBytes(StandardCharsets.UTF_8);
    }

    private String canonicalValue(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, Instant occurredAt
    ) {
        return field(eventId)
                + field(aggregateType)
                + field(aggregateId)
                + field(eventType)
                + field(String.valueOf(actorId))
                + field(payload)
                + field(occurredAt.toString());
    }

    private byte[] canonicalBytesWithCorrelation(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, Instant occurredAt, String correlationId
    ) {
        String canonical = canonicalValue(
                eventId, aggregateType, aggregateId, eventType, actorId, payload, occurredAt
        ) + field(correlationId);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private String field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    public record SignedAuditEvent(String keyId, String signature, int version) {
    }
}
