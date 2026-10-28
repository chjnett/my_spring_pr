package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.global.exception.DuplicateRoleGrantException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleGrantServiceTest {

    @Mock
    private RoleGrantRepository roleGrantRepository;

    @Mock
    private UserExistencePort userExistencePort;

    private RoleGrantService roleGrantService;

    @BeforeEach
    void setUp() {
        roleGrantService = new RoleGrantService(roleGrantRepository, userExistencePort);
    }

    @Test
    void grantsRoleToExistingUser() {
        when(userExistencePort.exists(1L)).thenReturn(true);
        when(roleGrantRepository.existsByUserIdAndRole(1L, Role.ADMIN)).thenReturn(false);
        when(roleGrantRepository.saveAndFlush(any(RoleGrant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GrantedRole result = roleGrantService.grantRole(1L, new GrantRoleCommand(Role.ADMIN));

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.role()).isEqualTo(Role.ADMIN);
        ArgumentCaptor<RoleGrant> captor = ArgumentCaptor.forClass(RoleGrant.class);
        verify(roleGrantRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void rejectsRoleGrantForMissingUser() {
        when(userExistencePort.exists(404L)).thenReturn(false);

        assertThatThrownBy(() -> roleGrantService.grantRole(404L, new GrantRoleCommand(Role.ADMIN)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void rejectsDuplicateRoleBeforeSave() {
        when(userExistencePort.exists(1L)).thenReturn(true);
        when(roleGrantRepository.existsByUserIdAndRole(1L, Role.ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> roleGrantService.grantRole(1L, new GrantRoleCommand(Role.ADMIN)))
                .isInstanceOf(DuplicateRoleGrantException.class);
    }

    @Test
    void translatesPostgresUniqueViolationToDuplicateRoleGrant() {
        SQLException postgresException = new SQLException(
                "duplicate key value violates unique constraint \"uk_role_grant_user_role\"",
                "23505"
        );
        when(userExistencePort.exists(1L)).thenReturn(true);
        when(roleGrantRepository.existsByUserIdAndRole(1L, Role.ADMIN)).thenReturn(false);
        when(roleGrantRepository.saveAndFlush(any(RoleGrant.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate role", postgresException));

        assertThatThrownBy(() -> roleGrantService.grantRole(1L, new GrantRoleCommand(Role.ADMIN)))
                .isInstanceOf(DuplicateRoleGrantException.class);
    }
}
