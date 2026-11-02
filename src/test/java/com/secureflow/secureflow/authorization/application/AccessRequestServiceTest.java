package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRequestRepository;
import com.secureflow.secureflow.global.exception.AccessRequestNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessRequestServiceTest {

    @Mock RoleGrantRequestRepository requestRepository;
    @Mock UserExistencePort userExistencePort;
    @Mock RoleGrantService roleGrantService;

    private AccessRequestService service;

    @BeforeEach
    void setUp() {
        service = new AccessRequestService(requestRepository, userExistencePort, roleGrantService);
    }

    @Test
    void createsPendingRequestForExistingUsers() {
        when(userExistencePort.exists(1L)).thenReturn(true);
        when(userExistencePort.exists(2L)).thenReturn(true);
        when(requestRepository.save(any(RoleGrantRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccessRequestResult result = service.create(
                1L, new CreateAccessRequestCommand(2L, Role.AUDITOR, "보안 로그 검토")
        );

        assertThat(result.requesterId()).isEqualTo(1L);
        assertThat(result.targetUserId()).isEqualTo(2L);
        assertThat(result.status()).isEqualTo(RoleGrantRequestStatus.PENDING);
    }

    @Test
    void approvalFlushesRequestBeforeGrantingRole() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.ADMIN, "배포 작업");
        when(userExistencePort.exists(3L)).thenReturn(true);
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        AccessRequestResult result = service.approve(10L, 3L, "승인");

        assertThat(result.status()).isEqualTo(RoleGrantRequestStatus.APPROVED);
        InOrder order = inOrder(requestRepository, roleGrantService);
        order.verify(requestRepository).saveAndFlush(request);
        order.verify(roleGrantService).grantRole(2L, new GrantRoleCommand(Role.ADMIN));
    }

    @Test
    void rejectsRequestWithoutGrantingRole() {
        RoleGrantRequest request = new RoleGrantRequest(1L, 2L, Role.ADMIN, "배포 작업");
        when(userExistencePort.exists(3L)).thenReturn(true);
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));

        AccessRequestResult result = service.reject(10L, 3L, "권한 범위가 너무 큼");

        assertThat(result.status()).isEqualTo(RoleGrantRequestStatus.REJECTED);
        verify(requestRepository).saveAndFlush(request);
    }

    @Test
    void missingRequestReturnsNotFound() {
        when(requestRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(AccessRequestNotFoundException.class);
    }
}
