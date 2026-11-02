package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.domain.AuditOutboxEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditOutboxEventTest {

    @Test
    void createsTimestampAtPostgresMicrosecondPrecision() {
        AuditOutboxEvent event = new AuditOutboxEvent(
                "RoleGrantRequest", "1", AuditEventType.ACCESS_REQUEST_CREATED,
                2L, "{}"
        );

        assertThat(event.getCreatedAt().getNano() % 1_000).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
    }
}
