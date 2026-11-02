package com.secureflow.secureflow.audit.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "secureflow.outbox.publisher")
public class OutboxPublisherProperties {

    @Min(1)
    @Max(1_000)
    private int batchSize = 100;

    @Min(1)
    @Max(100)
    private int maxAttempts = 5;

    @NotNull
    private Duration baseBackoff = Duration.ofSeconds(1);

    @NotNull
    private Duration maxBackoff = Duration.ofMinutes(5);

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBaseBackoff() {
        return baseBackoff;
    }

    public void setBaseBackoff(Duration baseBackoff) {
        this.baseBackoff = baseBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    @AssertTrue(message = "backoff는 음수가 아니고 max-backoff가 base-backoff 이상이어야 합니다.")
    public boolean isBackoffConfigurationValid() {
        return baseBackoff != null
                && maxBackoff != null
                && !baseBackoff.isNegative()
                && !maxBackoff.isNegative()
                && maxBackoff.compareTo(baseBackoff) >= 0;
    }

    public Duration retryDelay(int failedAttemptCount) {
        if (failedAttemptCount < 1) {
            throw new IllegalArgumentException("failedAttemptCount는 1 이상이어야 합니다.");
        }
        long multiplier = 1L << Math.min(failedAttemptCount - 1, 30);
        Duration delay;
        try {
            delay = baseBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return maxBackoff;
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
