package com.secureflow.secureflow.global.exception;

import com.secureflow.secureflow.audit.query.SecurityEventQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SecurityEventQueryException.class)
    public ResponseEntity<ErrorResponse> handleSecurityEventQuery(SecurityEventQueryException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SECURITY_EVENT_QUERY", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UserNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException exception) {
        return response(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateRoleGrantException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRoleGrant(DuplicateRoleGrantException exception) {
        return response(HttpStatus.CONFLICT, "DUPLICATE_ROLE_GRANT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReuse(IdempotencyKeyReuseException exception) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(IdempotencyRequestInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyRequestInProgress(IdempotencyRequestInProgressException exception) {
        return response(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccessRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccessRequestNotFound(AccessRequestNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ACCESS_REQUEST_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccessRequestAlreadyDecidedException.class)
    public ResponseEntity<ErrorResponse> handleAccessRequestAlreadyDecided(AccessRequestAlreadyDecidedException exception) {
        return response(HttpStatus.CONFLICT, "ACCESS_REQUEST_ALREADY_DECIDED", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(SelfApprovalNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSelfApproval(SelfApprovalNotAllowedException exception) {
        return response(HttpStatus.FORBIDDEN, "SELF_APPROVAL_NOT_ALLOWED", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
        return response(HttpStatus.CONFLICT, "ACCESS_REQUEST_CONCURRENTLY_MODIFIED",
                "다른 검토자가 먼저 권한 요청을 처리했습니다.", Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.", fieldErrors);
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(
                new ErrorResponse(Instant.now(), status.value(), code, message, fieldErrors));
    }
}
