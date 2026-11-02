package com.secureflow.secureflow.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_outbox")
public class AuditOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 80)
    private AuditEventType eventType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "integrity_key_id", length = 50)
    private String integrityKeyId;

    @Column(name = "integrity_signature", length = 64)
    private String integritySignature;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "integrity_version")
    private Integer integrityVersion;

    protected AuditOutboxEvent() {
    }

    public AuditOutboxEvent(
            String aggregateType,
            String aggregateId,
            AuditEventType eventType,
            Long actorId,
            String payload
    ) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public AuditEventType getEventType() { return eventType; }
    public Long getActorId() { return actorId; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getIntegrityKeyId() { return integrityKeyId; }
    public String getIntegritySignature() { return integritySignature; }
    public String getCorrelationId() { return correlationId; }
    public Integer getIntegrityVersion() { return integrityVersion; }

    public void sign(String keyId, String signature, String correlationId, int integrityVersion) {
        this.integrityKeyId = keyId;
        this.integritySignature = signature;
        this.correlationId = correlationId;
        this.integrityVersion = integrityVersion;
    }
}
