package com.secureflow.secureflow.audit.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "secureflow.outbox.publisher", name = "enabled", havingValue = "true")
public class AuditOutboxPollingJob {

    private final AuditOutboxPublisher publisher;
    private final OutboxPublisherProperties properties;

    public AuditOutboxPollingJob(
            AuditOutboxPublisher publisher,
            OutboxPublisherProperties properties
    ) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${secureflow.outbox.publisher.fixed-delay:1000}")
    public void publish() {
        publisher.publishBatch(properties.getBatchSize());
    }
}
