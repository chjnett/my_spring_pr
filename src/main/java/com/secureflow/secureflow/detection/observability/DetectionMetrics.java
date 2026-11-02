package com.secureflow.secureflow.detection.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class DetectionMetrics {

    private final Counter created;
    private final Counter duplicates;
    private final Timer eventToAlertLatency;

    public DetectionMetrics(MeterRegistry registry) {
        this.created = Counter.builder("secureflow.detection.alerts.created")
                .description("탐지 규칙이 생성한 경보 수")
                .register(registry);
        this.duplicates = Counter.builder("secureflow.detection.alerts.duplicates")
                .description("fingerprint로 중복 제거된 경보 수")
                .register(registry);
        this.eventToAlertLatency = Timer.builder("secureflow.detection.event.to.alert.latency")
                .description("마지막 원인 이벤트에서 경보 생성까지 걸린 시간")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordCreated(Instant lastEventAt, Instant createdAt) {
        created.increment();
        Duration latency = Duration.between(lastEventAt, createdAt);
        eventToAlertLatency.record(latency.isNegative() ? Duration.ZERO : latency);
    }

    public void recordDuplicate() {
        duplicates.increment();
    }
}
