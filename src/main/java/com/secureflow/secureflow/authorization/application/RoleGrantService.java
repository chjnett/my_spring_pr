package com.secureflow.secureflow.authorization.application;

import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.domain.IdempotencyRecord;
import com.secureflow.secureflow.authorization.observability.IdempotencyMetrics;
import com.secureflow.secureflow.authorization.persistence.IdempotencyRecordRepository;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.global.exception.DuplicateRoleGrantException;
import com.secureflow.secureflow.global.exception.IdempotencyKeyReuseException;
import com.secureflow.secureflow.global.exception.IdempotencyRequestInProgressException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class RoleGrantService {

    private static final String ROLE_GRANT_SCOPE = "POST:/api/users/{userId}/role-grants";

    private final RoleGrantRepository roleGrantRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final UserExistencePort userExistencePort;
    private final IdempotencyMetrics idempotencyMetrics;

    public RoleGrantService(
            RoleGrantRepository roleGrantRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            UserExistencePort userExistencePort,
            IdempotencyMetrics idempotencyMetrics
    ) {
        this.roleGrantRepository = roleGrantRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.userExistencePort = userExistencePort;
        this.idempotencyMetrics = idempotencyMetrics;
    }

    @Transactional
    public GrantedRole grantRole(Long userId, GrantRoleCommand command) {
        return createRoleGrant(userId, command);
    }

    /**
     * INSERT ... ON CONFLICT DO NOTHING의 반환값으로 최초 요청 한 건만 선점한다.
     * 최초 처리 결과는 같은 트랜잭션에서 idempotency_records에 기록한다.
     */
    @Transactional
    public IdempotentGrantedRole grantRoleIdempotently(
            Long userId,
            GrantRoleCommand command,
            String idempotencyKey
    ) {
        String requestHash = requestHash(command);
        int reserved = idempotencyRecordRepository.reserve(userId, ROLE_GRANT_SCOPE, idempotencyKey, requestHash);

        if (reserved == 0) {
            return replaySavedResult(userId, idempotencyKey, requestHash);
        }

        RoleGrant savedRoleGrant;
        try {
            savedRoleGrant = createRoleGrantEntity(userId, command);
        } catch (DuplicateRoleGrantException exception) {
            idempotencyMetrics.recordDuplicateRoleGrant();
            throw exception;
        }
        IdempotencyRecord record = idempotencyRecordRepository
                .findByUserIdAndRequestScopeAndIdempotencyKey(userId, ROLE_GRANT_SCOPE, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("선점한 멱등성 요청을 찾을 수 없습니다."));
        record.complete(savedRoleGrant);
        idempotencyMetrics.recordCreated();

        return new IdempotentGrantedRole(toGrantedRole(savedRoleGrant), false);
    }

    private IdempotentGrantedRole replaySavedResult(Long userId, String idempotencyKey, String requestHash) {
        IdempotencyRecord record = idempotencyRecordRepository
                .findByUserIdAndRequestScopeAndIdempotencyKey(userId, ROLE_GRANT_SCOPE, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("이미 처리된 멱등성 요청을 찾을 수 없습니다."));

        if (!record.hasSameRequest(requestHash)) {
            idempotencyMetrics.recordKeyReused();
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        if (!record.isCompleted()) {
            idempotencyMetrics.recordInProgress();
            throw new IdempotencyRequestInProgressException(idempotencyKey);
        }

        IdempotencyRecord.GrantedResponse response = record.completedResponse();
        idempotencyMetrics.recordReplayed();
        return new IdempotentGrantedRole(
                new GrantedRole(response.grantId(), response.userId(), response.role()),
                true
        );
    }

    private GrantedRole createRoleGrant(Long userId, GrantRoleCommand command) {
        return toGrantedRole(createRoleGrantEntity(userId, command));
    }

    private RoleGrant createRoleGrantEntity(Long userId, GrantRoleCommand command) {
        if (!userExistencePort.exists(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (roleGrantRepository.existsByUserIdAndRole(userId, command.role())) {
            throw new DuplicateRoleGrantException(userId, command.role());
        }

        try {
            return roleGrantRepository.saveAndFlush(
                    new RoleGrant(userId, command.role())
            );
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateRoleGrantConstraint(exception)) {
                throw new DuplicateRoleGrantException(userId, command.role(), exception);
            }
            throw exception;
        }
    }

    private GrantedRole toGrantedRole(RoleGrant roleGrant) {
        return new GrantedRole(roleGrant.getId(), roleGrant.getUserId(), roleGrant.getRole());
    }

    private String requestHash(GrantRoleCommand command) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(command.role().name().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
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
