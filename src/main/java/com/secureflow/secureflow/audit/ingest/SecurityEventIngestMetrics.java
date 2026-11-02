package com.secureflow.secureflow.audit.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SecurityEventIngestMetrics {

    private final Counter accepted;
    private final Counter duplicates;
    private final Counter rejected;
    private final DistributionSummary batchSize;

    public SecurityEventIngestMetrics(MeterRegistry registry) {
        this.accepted = counter(registry, "accepted", "정상 수집된 보안 이벤트 수");
        this.duplicates = counter(registry, "duplicate", "event_id 중복으로 건너뛴 보안 이벤트 수");
        this.rejected = counter(registry, "rejected", "입력 검증에서 거부된 보안 이벤트 수");
        this.batchSize = DistributionSummary.builder("secureflow.security.events.ingest.batch.size")
                .description("수집 요청 한 건에 포함된 이벤트 수")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void record(SecurityEventBatchResponse response) {
        accepted.increment(response.accepted());
        duplicates.increment(response.duplicate());
        rejected.increment(response.rejected());
        batchSize.record(response.results().size());
    }

    private Counter counter(MeterRegistry registry, String result, String description) {
        return Counter.builder("secureflow.security.events.ingest")
                .tag("result", result)
                .description(description)
                .register(registry);
    }
}
