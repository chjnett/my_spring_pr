package com.secureflow.secureflow.detection.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "secureflow.detection")
public class DetectionProperties {

    private boolean enabled;
    private Duration windowSize = Duration.ofMinutes(10);
    private Duration ingestionGrace = Duration.ofSeconds(30);

    @Min(1000)
    @Max(300000)
    private long fixedDelay = 30000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getWindowSize() { return windowSize; }
    public void setWindowSize(Duration windowSize) { this.windowSize = windowSize; }
    public Duration getIngestionGrace() { return ingestionGrace; }
    public void setIngestionGrace(Duration ingestionGrace) { this.ingestionGrace = ingestionGrace; }
    public long getFixedDelay() { return fixedDelay; }
    public void setFixedDelay(long fixedDelay) { this.fixedDelay = fixedDelay; }

    @AssertTrue(message = "탐지 window-size는 0초 초과 24시간 이하여야 하고 ingestion-grace는 음수일 수 없습니다.")
    public boolean isTimeConfigurationValid() {
        return windowSize != null && !windowSize.isZero() && !windowSize.isNegative()
                && windowSize.compareTo(Duration.ofDays(1)) <= 0
                && ingestionGrace != null && !ingestionGrace.isNegative();
    }
}
