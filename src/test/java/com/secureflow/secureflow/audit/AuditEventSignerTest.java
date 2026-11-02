package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditEventSigner;
import com.secureflow.secureflow.audit.application.AuditIntegrityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventSignerTest {

    @Test
    void verifiesOriginalFieldsAndRejectsChangedPayload() {
        AuditEventSigner signer = signer("test-v1", "01234567890123456789012345678901");
        Instant occurredAt = Instant.parse("2026-11-02T06:00:00Z");
        AuditEventSigner.SignedAuditEvent signed = signer.sign(
                "event-1", "RoleGrant", "10", "ROLE_GRANTED", 3L,
                "{\"role\":\"ADMIN\"}", occurredAt
        );

        assertThat(signer.verify(
                signed.keyId(), signed.signature(), "event-1", "RoleGrant", "10",
                "ROLE_GRANTED", 3L, "{\"role\":\"ADMIN\"}", occurredAt
        )).isTrue();
        assertThat(signer.verify(
                signed.keyId(), signed.signature(), "event-1", "RoleGrant", "10",
                "ROLE_GRANTED", 3L, "{\"role\":\"MEMBER\"}", occurredAt
        )).isFalse();
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> signer("test-v1", "too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void correlationIsPartOfVersionTwoSignature() {
        AuditEventSigner signer = signer("test-v1", "01234567890123456789012345678901");
        Instant occurredAt = Instant.parse("2026-11-02T06:00:00Z");
        AuditEventSigner.SignedAuditEvent signed = signer.signWithCorrelation(
                "event-2", "RoleGrantRequest", "11", "ACCESS_REQUEST_APPROVED",
                3L, "{\"role\":\"AUDITOR\"}", occurredAt, "request-abc"
        );

        assertThat(signed.version()).isEqualTo(AuditEventSigner.CORRELATION_VERSION);
        assertThat(signer.verifyWithCorrelation(
                signed.keyId(), signed.signature(), "event-2", "RoleGrantRequest", "11",
                "ACCESS_REQUEST_APPROVED", 3L, "{\"role\":\"AUDITOR\"}", occurredAt,
                "request-abc"
        )).isTrue();
        assertThat(signer.verifyWithCorrelation(
                signed.keyId(), signed.signature(), "event-2", "RoleGrantRequest", "11",
                "ACCESS_REQUEST_APPROVED", 3L, "{\"role\":\"AUDITOR\"}", occurredAt,
                "request-tampered"
        )).isFalse();
    }

    @Test
    void searchFieldsArePartOfVersionThreeSignature() {
        AuditEventSigner signer = signer("test-v3", "01234567890123456789012345678901");
        Instant occurredAt = Instant.parse("2026-11-02T06:00:00Z");
        AuditEventSigner.SignedAuditEvent signed = signer.signWithSearchFields(
                "event-3", "ExternalSecurityEvent", "event-3", "LOGIN_FAILED",
                3L, "{\"reason\":\"BAD_PASSWORD\"}", occurredAt, "request-v3",
                "identity-service", "192.0.2.10", "FAILURE"
        );

        assertThat(signed.version()).isEqualTo(AuditEventSigner.SEARCH_FIELDS_VERSION);
        assertThat(signer.verifyWithSearchFields(
                signed.keyId(), signed.signature(), "event-3", "ExternalSecurityEvent", "event-3",
                "LOGIN_FAILED", 3L, "{\"reason\":\"BAD_PASSWORD\"}", occurredAt, "request-v3",
                "identity-service", "192.0.2.10", "FAILURE"
        )).isTrue();
        assertThat(signer.verifyWithSearchFields(
                signed.keyId(), signed.signature(), "event-3", "ExternalSecurityEvent", "event-3",
                "LOGIN_FAILED", 3L, "{\"reason\":\"BAD_PASSWORD\"}", occurredAt, "request-v3",
                "identity-service", "192.0.2.99", "FAILURE"
        )).isFalse();
    }

    @Test
    void verifiesEventsSignedByPreviousKeyAfterRotation() {
        String oldSecret = "old-secret-01234567890123456789012";
        AuditEventSigner oldSigner = signer("old-v1", oldSecret);
        Instant occurredAt = Instant.parse("2026-11-02T06:00:00Z");
        AuditEventSigner.SignedAuditEvent oldEvent = oldSigner.signWithCorrelation(
                "event-old", "RoleGrant", "12", "ROLE_GRANTED", 3L,
                "{\"role\":\"ADMIN\"}", occurredAt, "request-old"
        );

        AuditIntegrityProperties rotatedProperties = properties(
                "new-v2", "new-secret-01234567890123456789012"
        );
        rotatedProperties.setVerificationKeys(Map.of("old-v1", oldSecret));
        AuditEventSigner rotatedSigner = new AuditEventSigner(rotatedProperties);

        assertThat(rotatedSigner.supports("old-v1")).isTrue();
        assertThat(rotatedSigner.verifyWithCorrelation(
                oldEvent.keyId(), oldEvent.signature(), "event-old", "RoleGrant", "12",
                "ROLE_GRANTED", 3L, "{\"role\":\"ADMIN\"}", occurredAt, "request-old"
        )).isTrue();
        assertThat(rotatedSigner.sign(
                "event-new", "RoleGrant", "13", "ROLE_GRANTED", 3L,
                "{\"role\":\"AUDITOR\"}", occurredAt
        ).keyId()).isEqualTo("new-v2");
    }

    private AuditEventSigner signer(String keyId, String secret) {
        return new AuditEventSigner(properties(keyId, secret));
    }

    private AuditIntegrityProperties properties(String keyId, String secret) {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setKeyId(keyId);
        properties.setSecret(secret);
        return properties;
    }
}
