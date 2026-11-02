package com.secureflow.secureflow.authorization.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 멱등성 키 값은 label로 기록하지 않는다.
 * 키마다 시계열이 늘어나는 high-cardinality 문제를 피하고, 결과 종류만 관찰한다.
 */
@Component
public class IdempotencyMetrics {

    private static final String METRIC_NAME = "secureflow.idempotency.requests";
    private final MeterRegistry meterRegistry;

    public IdempotencyMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCreated() {
        increment("created");
    }

    public void recordReplayed() {
        increment("replayed");
    }

    public void recordInProgress() {
        increment("in_progress");
    }

    public void recordKeyReused() {
        increment("key_reused");
    }

    public void recordDuplicateRoleGrant() {
        increment("duplicate_role_grant");
    }

    private void increment(String outcome) {
        meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
