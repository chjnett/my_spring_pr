package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditEventSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventSignerTest {

    @Test
    void verifiesOriginalFieldsAndRejectsChangedPayload() {
        AuditEventSigner signer = new AuditEventSigner(
                "test-v1", "01234567890123456789012345678901"
        );
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
        assertThatThrownBy(() -> new AuditEventSigner("test-v1", "too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void correlationIsPartOfVersionTwoSignature() {
        AuditEventSigner signer = new AuditEventSigner(
                "test-v1", "01234567890123456789012345678901"
        );
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
}
