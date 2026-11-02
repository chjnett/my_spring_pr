package com.secureflow.secureflow.global.exception;

import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;

public class AccessRequestAlreadyDecidedException extends RuntimeException {
    public AccessRequestAlreadyDecidedException(Long requestId, RoleGrantRequestStatus status) {
        super("이미 처리된 권한 요청입니다. requestId=" + requestId + ", status=" + status);
    }
}
