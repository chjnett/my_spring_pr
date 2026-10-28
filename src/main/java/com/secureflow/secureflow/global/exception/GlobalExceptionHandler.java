package com.secureflow.secureflow.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
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
