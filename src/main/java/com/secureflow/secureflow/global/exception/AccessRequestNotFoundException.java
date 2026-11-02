package com.secureflow.secureflow.global.exception;

public class AccessRequestNotFoundException extends RuntimeException {
    public AccessRequestNotFoundException(Long requestId) {
        super("권한 요청을 찾을 수 없습니다. requestId=" + requestId);
    }
}
