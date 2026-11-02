package com.secureflow.secureflow.detection.web;

import com.secureflow.secureflow.detection.domain.AlertSeverity;
import com.secureflow.secureflow.detection.domain.AlertStatus;
import com.secureflow.secureflow.detection.domain.DetectionRule;
import com.secureflow.secureflow.detection.domain.SecurityAlert;

import java.time.Instant;

public record AlertResponse(
        Long id,
        DetectionRule rule,
        AlertSeverity severity,
        AlertStatus status,
        String subjectType,
        String subjectValue,
        long eventCount,
        Instant windowStart,
        Instant windowEnd,
        Instant firstEventAt,
        Instant lastEventAt,
        Long assigneeId,
        String resolutionNote,
        Long updatedBy,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AlertResponse from(SecurityAlert alert) {
        return new AlertResponse(
                alert.getId(), alert.getRuleCode(), alert.getSeverity(), alert.getStatus(),
                alert.getSubjectType(), alert.getSubjectValue(), alert.getEventCount(),
                alert.getWindowStart(), alert.getWindowEnd(), alert.getFirstEventAt(),
                alert.getLastEventAt(), alert.getAssigneeId(), alert.getResolutionNote(),
                alert.getUpdatedBy(), alert.getVersion(), alert.getCreatedAt(), alert.getUpdatedAt()
        );
    }
}
