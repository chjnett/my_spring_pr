package com.secureflow.secureflow.audit.application;

import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.domain.AuditOutboxEvent;
import com.secureflow.secureflow.audit.persistence.AuditOutboxRepository;
import com.secureflow.secureflow.global.observability.CorrelationIdContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditEventRecorder {

    private final AuditOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final AuditEventSigner signer;

    public AuditEventRecorder(
            AuditOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            AuditEventSigner signer
    ) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public void record(
            AuditEventType eventType,
            String aggregateType,
            Object aggregateId,
            Long actorId,
            Object payload
    ) {
        String payloadJson = objectMapper.writeValueAsString(payload);
        AuditOutboxEvent event = new AuditOutboxEvent(
                aggregateType, String.valueOf(aggregateId), eventType, actorId, payloadJson
        );
        String correlationId = CorrelationIdContext.currentOrCreate();
        AuditEventSigner.SignedAuditEvent signature = signer.signWithCorrelation(
                event.getEventId(), event.getAggregateType(), event.getAggregateId(),
                event.getEventType().name(), event.getActorId(), event.getPayload(),
                event.getCreatedAt(), correlationId
        );
        event.sign(signature.keyId(), signature.signature(), correlationId, signature.version());
        outboxRepository.save(event);
    }
}
