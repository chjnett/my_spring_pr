package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuditOutboxPublisherTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditOutboxPublisher publisher = new AuditOutboxPublisher(jdbcTemplate);

    @Test
    void rejectsBatchSizeOutsideSafeRange() {
        assertThatThrownBy(() -> publisher.publishBatch(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publishBatch(1_001))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jdbcTemplate);
    }
}
