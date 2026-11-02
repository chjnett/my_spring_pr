package com.secureflow.secureflow.audit.persistence;

import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.domain.AuditOutboxEvent;
import com.secureflow.secureflow.audit.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, Long> {
    long countByEventType(AuditEventType eventType);
    long countByStatus(OutboxStatus status);
}
