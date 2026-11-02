package com.secureflow.secureflow.detection.application;

import java.time.Instant;

public record DetectionRunResult(
        Instant from,
        Instant to,
        int candidates,
        int created,
        int duplicates
) {
}
