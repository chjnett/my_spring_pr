package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRequestRepository;
import com.secureflow.secureflow.global.exception.AccessRequestNotFoundException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccessRequestService {

    private final RoleGrantRequestRepository requestRepository;
    private final UserExistencePort userExistencePort;
    private final RoleGrantService roleGrantService;

    public AccessRequestService(
            RoleGrantRequestRepository requestRepository,
            UserExistencePort userExistencePort,
            RoleGrantService roleGrantService
    ) {
        this.requestRepository = requestRepository;
        this.userExistencePort = userExistencePort;
        this.roleGrantService = roleGrantService;
    }

    @Transactional
    public AccessRequestResult create(Long requesterId, CreateAccessRequestCommand command) {
        requireExistingUser(requesterId);
        requireExistingUser(command.targetUserId());

        RoleGrantRequest saved = requestRepository.save(new RoleGrantRequest(
                requesterId, command.targetUserId(), command.role(), command.reason()
        ));
        return AccessRequestResult.from(saved);
    }

    @Transactional
    public AccessRequestResult approve(Long requestId, Long reviewerId, String decisionReason) {
        requireExistingUser(reviewerId);
        RoleGrantRequest request = findRequest(requestId);
        request.approve(reviewerId, decisionReason);

        // version 증가를 역할 부여보다 먼저 실행해 동시 승인 한 건만 통과시킨다.
        requestRepository.saveAndFlush(request);
        roleGrantService.grantRole(
                request.getTargetUserId(),
                new GrantRoleCommand(request.getRequestedRole())
        );
        return AccessRequestResult.from(request);
    }

    @Transactional
    public AccessRequestResult reject(Long requestId, Long reviewerId, String decisionReason) {
        requireExistingUser(reviewerId);
        RoleGrantRequest request = findRequest(requestId);
        request.reject(reviewerId, decisionReason);
        requestRepository.saveAndFlush(request);
        return AccessRequestResult.from(request);
    }

    @Transactional(readOnly = true)
    public AccessRequestResult get(Long requestId) {
        return AccessRequestResult.from(findRequest(requestId));
    }

    @Transactional(readOnly = true)
    public List<AccessRequestResult> list(RoleGrantRequestStatus status) {
        List<RoleGrantRequest> requests = status == null
                ? requestRepository.findAll()
                : requestRepository.findAllByStatusOrderByCreatedAtDesc(status);
        return requests.stream().map(AccessRequestResult::from).toList();
    }

    private RoleGrantRequest findRequest(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(requestId));
    }

    private void requireExistingUser(Long userId) {
        if (!userExistencePort.exists(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
