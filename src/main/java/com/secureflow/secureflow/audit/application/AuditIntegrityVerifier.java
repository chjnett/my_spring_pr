package com.secureflow.secureflow.audit.application;

import com.secureflow.secureflow.audit.domain.AuditIntegrityStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AuditIntegrityVerifier {

    private final JdbcTemplate jdbcTemplate;
    private final AuditEventSigner signer;

    public AuditIntegrityVerifier(JdbcTemplate jdbcTemplate, AuditEventSigner signer) {
        this.jdbcTemplate = jdbcTemplate;
        this.signer = signer;
    }

    public AuditIntegrityStatus verifySecurityEvent(String eventId) {
        List<StoredAuditEvent> events = jdbcTemplate.query("""
                select event_id, aggregate_type, aggregate_id, event_type, actor_id,
                       payload, occurred_at, integrity_key_id, integrity_signature,
                       correlation_id, integrity_version
                from security_events
                where event_id = ?
                """, (resultSet, rowNumber) -> new StoredAuditEvent(
                resultSet.getString("event_id"), resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"), resultSet.getString("event_type"),
                resultSet.getLong("actor_id"), resultSet.getString("payload"),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getString("integrity_key_id"), resultSet.getString("integrity_signature"),
                resultSet.getString("correlation_id"),
                resultSet.getObject("integrity_version", Integer.class)
        ), eventId);

        if (events.isEmpty()) {
            throw new IllegalArgumentException("보안 이벤트를 찾을 수 없습니다: " + eventId);
        }
        StoredAuditEvent event = events.getFirst();
        if (event.keyId() == null || event.signature() == null) {
            return AuditIntegrityStatus.UNSIGNED;
        }
        if (!signer.supports(event.keyId())) {
            return AuditIntegrityStatus.UNKNOWN_KEY;
        }
        int version = event.integrityVersion() == null
                ? AuditEventSigner.LEGACY_VERSION : event.integrityVersion();
        boolean valid;
        if (version == AuditEventSigner.LEGACY_VERSION) {
            valid = signer.verify(
                    event.keyId(), event.signature(), event.eventId(), event.aggregateType(),
                    event.aggregateId(), event.eventType(), event.actorId(), event.payload(),
                    event.occurredAt().toInstant()
            );
        } else if (version == AuditEventSigner.CORRELATION_VERSION && event.correlationId() != null) {
            valid = signer.verifyWithCorrelation(
                    event.keyId(), event.signature(), event.eventId(), event.aggregateType(),
                    event.aggregateId(), event.eventType(), event.actorId(), event.payload(),
                    event.occurredAt().toInstant(), event.correlationId()
            );
        } else {
            return AuditIntegrityStatus.UNKNOWN_VERSION;
        }
        return valid ? AuditIntegrityStatus.VALID : AuditIntegrityStatus.INVALID;
    }

    private record StoredAuditEvent(
            String eventId, String aggregateType, String aggregateId, String eventType,
            Long actorId, String payload, OffsetDateTime occurredAt, String keyId, String signature,
            String correlationId, Integer integrityVersion
    ) {
    }
}
