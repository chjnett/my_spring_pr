package com.secureflow.secureflow.detection.application;

import com.secureflow.secureflow.detection.domain.AlertStatus;
import com.secureflow.secureflow.detection.domain.SecurityAlert;
import com.secureflow.secureflow.detection.exception.AlertConcurrentModificationException;
import com.secureflow.secureflow.detection.exception.AlertNotFoundException;
import com.secureflow.secureflow.detection.persistence.SecurityAlertRepository;
import com.secureflow.secureflow.detection.observability.AlertWorkflowMetrics;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import com.secureflow.secureflow.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AlertService {

    private final SecurityAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final AlertWorkflowMetrics metrics;

    public AlertService(
            SecurityAlertRepository alertRepository,
            UserRepository userRepository,
            AlertWorkflowMetrics metrics
    ) {
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public SecurityAlert get(Long id) {
        return alertRepository.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<SecurityAlert> list(AlertStatus status) {
        return status == null
                ? alertRepository.findTop200ByOrderByCreatedAtDesc()
                : alertRepository.findTop200ByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public SecurityAlert assign(Long alertId, Long assigneeId, Long actorId) {
        if (!userRepository.existsById(assigneeId)) {
            throw new UserNotFoundException(assigneeId);
        }
        SecurityAlert alert = getForUpdate(alertId);
        alert.assign(assigneeId, actorId, Instant.now());
        SecurityAlert saved = save(alertId, alert);
        metrics.recordAssigned();
        return saved;
    }

    @Transactional
    public SecurityAlert resolve(Long alertId, String resolutionNote, Long actorId) {
        SecurityAlert alert = getForUpdate(alertId);
        alert.resolve(resolutionNote, actorId, Instant.now());
        SecurityAlert saved = save(alertId, alert);
        metrics.recordResolved();
        return saved;
    }

    private SecurityAlert getForUpdate(Long id) {
        return alertRepository.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
    }

    private SecurityAlert save(Long alertId, SecurityAlert alert) {
        try {
            return alertRepository.saveAndFlush(alert);
        } catch (ObjectOptimisticLockingFailureException exception) {
            metrics.recordConflict();
            throw new AlertConcurrentModificationException(alertId, exception);
        }
    }
}
