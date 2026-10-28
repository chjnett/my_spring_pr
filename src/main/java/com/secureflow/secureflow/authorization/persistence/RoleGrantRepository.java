package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleGrantRepository extends JpaRepository<RoleGrant, Long> {
    boolean existsByUserIdAndRole(Long userId, Role role);

    long countByUserIdAndRole(Long userId, Role role);
}
