package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.OutboxPublisherProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPublisherPropertiesTest {

    @Test
    void calculatesExponentialBackoffAndCapsIt() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties();
        properties.setBaseBackoff(Duration.ofSeconds(2));
        properties.setMaxBackoff(Duration.ofSeconds(10));

        assertThat(properties.retryDelay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.retryDelay(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.retryDelay(4)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(30)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsNonPositiveAttemptCount() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties();

        assertThatThrownBy(() -> properties.retryDelay(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
