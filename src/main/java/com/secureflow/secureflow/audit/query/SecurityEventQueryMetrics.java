package com.secureflow.secureflow.audit.query;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class SecurityEventQueryMetrics {

    private final Timer latency;
    private final DistributionSummary resultSize;

    public SecurityEventQueryMetrics(MeterRegistry registry) {
        this.latency = Timer.builder("secureflow.security.events.search.latency")
                .description("보안 이벤트 검색 처리 시간")
                .publishPercentileHistogram()
                .register(registry);
        this.resultSize = DistributionSummary.builder("secureflow.security.events.search.result.size")
                .description("보안 이벤트 검색 응답 건수")
                .publishPercentileHistogram()
                .register(registry);
    }

    public SecurityEventPage record(Supplier<SecurityEventPage> query) {
        return latency.record(() -> {
            SecurityEventPage page = query.get();
            resultSize.record(page.events().size());
            return page;
        });
    }
}
