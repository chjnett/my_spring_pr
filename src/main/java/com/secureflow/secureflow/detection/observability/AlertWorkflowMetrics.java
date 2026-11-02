package com.secureflow.secureflow.detection.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AlertWorkflowMetrics {

    private final Counter assigned;
    private final Counter resolved;
    private final Counter conflicts;

    public AlertWorkflowMetrics(MeterRegistry registry) {
        this.assigned = counter(registry, "assigned", "담당자가 지정된 경보 수");
        this.resolved = counter(registry, "resolved", "해결 처리된 경보 수");
        this.conflicts = counter(registry, "optimistic_lock_conflict", "낙관적 락 충돌 수");
    }

    public void recordAssigned() { assigned.increment(); }
    public void recordResolved() { resolved.increment(); }
    public void recordConflict() { conflicts.increment(); }

    private Counter counter(MeterRegistry registry, String action, String description) {
        return Counter.builder("secureflow.alert.workflow")
                .tag("action", action)
                .description(description)
                .register(registry);
    }
}
