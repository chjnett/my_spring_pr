package com.secureflow.secureflow.detection.application;

import com.secureflow.secureflow.detection.domain.SecurityAlert;
import com.secureflow.secureflow.detection.observability.DetectionMetrics;
import com.secureflow.secureflow.detection.persistence.DetectionQueryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class DetectionService {

    private static final Duration MAX_WINDOW = Duration.ofDays(1);

    private final DetectionQueryRepository queryRepository;
    private final AlertCreationService alertCreationService;
    private final DetectionMetrics metrics;

    public DetectionService(
            DetectionQueryRepository queryRepository,
            AlertCreationService alertCreationService,
            DetectionMetrics metrics
    ) {
        this.queryRepository = queryRepository;
        this.alertCreationService = alertCreationService;
        this.metrics = metrics;
    }

    public DetectionRunResult detectWindow(Instant from, Instant to) {
        validateWindow(from, to);
        List<DetectionCandidate> candidates = queryRepository.findCandidates(from, to);
        int created = 0;
        int duplicates = 0;
        for (DetectionCandidate candidate : candidates) {
            try {
                SecurityAlert alert = alertCreationService.create(candidate);
                metrics.recordCreated(candidate.lastEventAt(), alert.getCreatedAt());
                created++;
            } catch (DataIntegrityViolationException exception) {
                metrics.recordDuplicate();
                duplicates++;
            }
        }
        return new DetectionRunResult(from, to, candidates.size(), created, duplicates);
    }

    private void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("탐지 구간은 from < to여야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("한 번의 탐지 구간은 최대 24시간입니다.");
        }
    }
}
