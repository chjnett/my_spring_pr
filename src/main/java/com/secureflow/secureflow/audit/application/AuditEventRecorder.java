package com.secureflow.secureflow.audit.application;

import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.domain.AuditOutboxEvent;
import com.secureflow.secureflow.audit.persistence.AuditOutboxRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditEventRecorder {

    private final AuditOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AuditEventRecorder(AuditOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void record(
            AuditEventType eventType,
            String aggregateType,
            Object aggregateId,
            Long actorId,
            Object payload
    ) {
        String payloadJson = objectMapper.writeValueAsString(payload);
        outboxRepository.save(new AuditOutboxEvent(
                aggregateType, String.valueOf(aggregateId), eventType, actorId, payloadJson
        ));
    }
}
