package com.secureflow.secureflow.audit.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final Counter published;
    private final Counter retries;
    private final Counter failures;
    private final Counter deadLetters;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();

    public OutboxMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.published = Counter.builder("secureflow.outbox.published")
                .description("성공적으로 발행한 Outbox 이벤트 수")
                .register(meterRegistry);
        this.retries = Counter.builder("secureflow.outbox.retries")
                .description("재시도하도록 예약한 Outbox 이벤트 수")
                .register(meterRegistry);
        this.failures = Counter.builder("secureflow.outbox.failures")
                .description("Outbox 발행 시도 실패 수")
                .register(meterRegistry);
        this.deadLetters = Counter.builder("secureflow.outbox.dead.letters")
                .description("최대 시도 횟수를 초과해 격리한 이벤트 수")
                .register(meterRegistry);
        Gauge.builder("secureflow.outbox.pending", pendingCount, AtomicLong::doubleValue)
                .description("현재 발행 대기 중인 Outbox 이벤트 수")
                .register(meterRegistry);
        Gauge.builder("secureflow.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("가장 오래된 발행 대기 이벤트의 나이")
                .baseUnit("seconds")
                .register(meterRegistry);
        Gauge.builder("secureflow.outbox.dead.letter.count", deadLetterCount, AtomicLong::doubleValue)
                .description("현재 Dead Letter 이벤트 수")
                .register(meterRegistry);
    }

    public void recordPublished() {
        published.increment();
    }

    public void recordFailure(boolean deadLettered) {
        failures.increment();
        if (deadLettered) {
            deadLetters.increment();
        } else {
            retries.increment();
        }
    }

    public void refresh() {
        Long pending = jdbcTemplate.queryForObject(
                "select count(*) from audit_outbox where status = 'PENDING'", Long.class
        );
        OffsetDateTime oldest = jdbcTemplate.queryForObject(
                "select min(created_at) from audit_outbox where status = 'PENDING'",
                OffsetDateTime.class
        );
        Long dead = jdbcTemplate.queryForObject(
                "select count(*) from audit_outbox_dead_letter", Long.class
        );

        pendingCount.set(pending == null ? 0 : pending);
        deadLetterCount.set(dead == null ? 0 : dead);
        oldestPendingAgeSeconds.set(oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, OffsetDateTime.now(ZoneOffset.UTC)).toSeconds()));
    }
}
