package com.secureflow.secureflow.global.exception;

public class IdempotencyRequestInProgressException extends RuntimeException {
    public IdempotencyRequestInProgressException(String idempotencyKey) {
        super("같은 Idempotency-Key 요청이 아직 처리 중입니다. 잠시 후 같은 키로 재시도해 주세요: " + idempotencyKey);
    }
}
