package com.secureflow.secureflow.detection.application;

import com.secureflow.secureflow.detection.domain.DetectionRule;

import java.time.Instant;

public record DetectionCandidate(
        DetectionRule rule,
        String subjectValue,
        long eventCount,
        Instant windowStart,
        Instant windowEnd,
        Instant firstEventAt,
        Instant lastEventAt
) {
}
