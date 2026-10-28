package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.global.exception.DuplicateRoleGrantException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

@Service
public class RoleGrantService {

    private final RoleGrantRepository roleGrantRepository;
    private final UserExistencePort userExistencePort;

    public RoleGrantService(
            RoleGrantRepository roleGrantRepository,
            UserExistencePort userExistencePort
    ) {
        this.roleGrantRepository = roleGrantRepository;
        this.userExistencePort = userExistencePort;
    }

    @Transactional
    public GrantedRole grantRole(Long userId, GrantRoleCommand command) {
        if (!userExistencePort.exists(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (roleGrantRepository.existsByUserIdAndRole(userId, command.role())) {
            throw new DuplicateRoleGrantException(userId, command.role());
        }

        try {
            RoleGrant savedRoleGrant = roleGrantRepository.saveAndFlush(
                    new RoleGrant(userId, command.role())
            );
            return new GrantedRole(
                    savedRoleGrant.getId(),
                    savedRoleGrant.getUserId(),
                    savedRoleGrant.getRole()
            );
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateRoleGrantConstraint(exception)) {
                throw new DuplicateRoleGrantException(userId, command.role(), exception);
            }
            throw exception;
        }
    }

    private boolean isDuplicateRoleGrantConstraint(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("uk_role_grant_user_role")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
