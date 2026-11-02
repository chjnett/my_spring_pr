package com.secureflow.secureflow.detection.persistence;

import com.secureflow.secureflow.detection.domain.AlertStatus;
import com.secureflow.secureflow.detection.domain.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {
    List<SecurityAlert> findTop200ByOrderByCreatedAtDesc();
    List<SecurityAlert> findTop200ByStatusOrderByCreatedAtDesc(AlertStatus status);
}
