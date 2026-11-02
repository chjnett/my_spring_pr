package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.authorization.domain.IdempotencyRecord;
import com.secureflow.secureflow.authorization.persistence.IdempotencyRecordRepository;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRequestRepository;
import com.secureflow.secureflow.global.exception.AccessRequestNotFoundException;
import com.secureflow.secureflow.global.exception.IdempotencyKeyReuseException;
import com.secureflow.secureflow.global.exception.IdempotencyRequestInProgressException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import com.secureflow.secureflow.audit.application.AuditEventRecorder;
import com.secureflow.secureflow.audit.domain.AuditEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class AccessRequestService {

    private static final String CREATE_ACCESS_REQUEST_SCOPE = "POST:/api/access-requests";

    private final RoleGrantRequestRepository requestRepository;
    private final UserExistencePort userExistencePort;
    private final RoleGrantService roleGrantService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final AuditEventRecorder auditEventRecorder;

    public AccessRequestService(
            RoleGrantRequestRepository requestRepository,
            UserExistencePort userExistencePort,
            RoleGrantService roleGrantService,
            IdempotencyRecordRepository idempotencyRecordRepository,
            AuditEventRecorder auditEventRecorder
    ) {
        this.requestRepository = requestRepository;
        this.userExistencePort = userExistencePort;
        this.roleGrantService = roleGrantService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.auditEventRecorder = auditEventRecorder;
    }

    @Transactional
    public IdempotentAccessRequestResult createIdempotently(
            Long requesterId,
            CreateAccessRequestCommand command,
            String idempotencyKey
    ) {
        requireExistingUser(requesterId);
        requireExistingUser(command.targetUserId());

        String requestHash = requestHash(command);
        int reserved = idempotencyRecordRepository.reserve(
                requesterId, CREATE_ACCESS_REQUEST_SCOPE, idempotencyKey, requestHash
        );
        if (reserved == 0) {
            return replayCreatedRequest(requesterId, idempotencyKey, requestHash);
        }

        RoleGrantRequest request = requestRepository.saveAndFlush(new RoleGrantRequest(
                requesterId, command.targetUserId(), command.role(), command.reason()
        ));
        IdempotencyRecord record = findIdempotencyRecord(requesterId, idempotencyKey);
        record.complete(request);
        auditEventRecorder.record(
                AuditEventType.ACCESS_REQUEST_CREATED,
                "RoleGrantRequest",
                request.getId(),
                requesterId,
                new AccessRequestCreatedPayload(
                        command.targetUserId(), command.role().name(), command.reason()
                )
        );
        return new IdempotentAccessRequestResult(AccessRequestResult.createdSnapshot(request), false);
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
        auditEventRecorder.record(
                AuditEventType.ACCESS_REQUEST_APPROVED,
                "RoleGrantRequest",
                request.getId(),
                reviewerId,
                new AccessRequestReviewedPayload(
                        request.getTargetUserId(), request.getRequestedRole().name(), decisionReason
                )
        );
        return AccessRequestResult.from(request);
    }

    @Transactional
    public AccessRequestResult reject(Long requestId, Long reviewerId, String decisionReason) {
        requireExistingUser(reviewerId);
        RoleGrantRequest request = findRequest(requestId);
        request.reject(reviewerId, decisionReason);
        requestRepository.saveAndFlush(request);
        auditEventRecorder.record(
                AuditEventType.ACCESS_REQUEST_REJECTED,
                "RoleGrantRequest",
                request.getId(),
                reviewerId,
                new AccessRequestReviewedPayload(
                        request.getTargetUserId(), request.getRequestedRole().name(), decisionReason
                )
        );
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

    private IdempotentAccessRequestResult replayCreatedRequest(
            Long requesterId,
            String idempotencyKey,
            String requestHash
    ) {
        IdempotencyRecord record = findIdempotencyRecord(requesterId, idempotencyKey);
        if (!record.hasSameRequest(requestHash)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        if (!record.isCompleted()) {
            throw new IdempotencyRequestInProgressException(idempotencyKey);
        }

        RoleGrantRequest request = findRequest(record.completedAccessRequestId());
        return new IdempotentAccessRequestResult(AccessRequestResult.createdSnapshot(request), true);
    }

    private IdempotencyRecord findIdempotencyRecord(Long requesterId, String idempotencyKey) {
        return idempotencyRecordRepository
                .findByUserIdAndRequestScopeAndIdempotencyKey(
                        requesterId, CREATE_ACCESS_REQUEST_SCOPE, idempotencyKey
                )
                .orElseThrow(() -> new IllegalStateException("선점한 권한 요청 멱등성 기록을 찾을 수 없습니다."));
    }

    private String requestHash(CreateAccessRequestCommand command) {
        String canonicalRequest = command.targetUserId() + "|" + command.role().name() + "|" + command.reason();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private record AccessRequestCreatedPayload(Long targetUserId, String role, String reason) {
    }

    private record AccessRequestReviewedPayload(Long targetUserId, String role, String decisionReason) {
    }
}
