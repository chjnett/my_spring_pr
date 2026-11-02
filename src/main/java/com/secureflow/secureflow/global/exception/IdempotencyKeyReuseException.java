package com.secureflow.secureflow.global.exception;

public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("같은 Idempotency-Key에는 최초 요청과 같은 내용만 사용할 수 있습니다: " + idempotencyKey);
    }
}
