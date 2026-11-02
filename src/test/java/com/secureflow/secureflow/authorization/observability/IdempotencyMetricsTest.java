package com.secureflow.secureflow.authorization.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyMetricsTest {

    @Test
    void recordsOutcomesWithBoundedLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdempotencyMetrics metrics = new IdempotencyMetrics(registry);

        metrics.recordCreated();
        metrics.recordReplayed();
        metrics.recordReplayed();
        metrics.recordKeyReused();

        assertThat(counter(registry, "created")).isEqualTo(1.0);
        assertThat(counter(registry, "replayed")).isEqualTo(2.0);
        assertThat(counter(registry, "key_reused")).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getKey().equals("idempotency_key"))
        );
    }

    private double counter(SimpleMeterRegistry registry, String outcome) {
        return registry.get("secureflow.idempotency.requests")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
