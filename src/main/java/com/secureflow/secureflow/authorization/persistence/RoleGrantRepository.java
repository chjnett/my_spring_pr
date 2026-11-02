package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleGrantRepository extends JpaRepository<RoleGrant, Long> {
    boolean existsByUserIdAndRole(Long userId, Role role);

    long countByUserIdAndRole(Long userId, Role role);

    List<RoleGrant> findAllByUserId(Long userId);
}
