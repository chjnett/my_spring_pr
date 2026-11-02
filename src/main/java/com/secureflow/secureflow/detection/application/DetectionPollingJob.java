package com.secureflow.secureflow.detection.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "secureflow.detection", name = "enabled", havingValue = "true")
public class DetectionPollingJob {

    private final DetectionWatermarkService watermarkService;

    public DetectionPollingJob(DetectionWatermarkService watermarkService) {
        this.watermarkService = watermarkService;
    }

    @Scheduled(fixedDelayString = "${secureflow.detection.fixed-delay:30000}")
    public void detect() {
        watermarkService.runOnce(Instant.now());
    }
}
