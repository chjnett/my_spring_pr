package com.secureflow.secureflow.detection.application;

import com.secureflow.secureflow.detection.domain.SecurityAlert;
import com.secureflow.secureflow.detection.persistence.SecurityAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

@Service
public class AlertCreationService {

    private final SecurityAlertRepository alertRepository;
    private final Clock clock = Clock.systemUTC();

    public AlertCreationService(SecurityAlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SecurityAlert create(DetectionCandidate candidate) {
        SecurityAlert alert = new SecurityAlert(fingerprint(candidate), candidate, clock.instant());
        return alertRepository.saveAndFlush(alert);
    }

    String fingerprint(DetectionCandidate candidate) {
        String canonical = String.join("|",
                candidate.rule().name(),
                candidate.subjectValue(),
                candidate.windowStart().toString(),
                candidate.windowEnd().toString()
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
