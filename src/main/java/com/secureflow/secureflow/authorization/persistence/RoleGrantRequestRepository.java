package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleGrantRequestRepository extends JpaRepository<RoleGrantRequest, Long> {
    List<RoleGrantRequest> findAllByStatusOrderByCreatedAtDesc(RoleGrantRequestStatus status);
}
