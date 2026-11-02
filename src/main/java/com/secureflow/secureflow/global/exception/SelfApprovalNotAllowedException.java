package com.secureflow.secureflow.global.exception;

public class SelfApprovalNotAllowedException extends RuntimeException {
    public SelfApprovalNotAllowedException(Long requestId, Long reviewerId) {
        super("자신이 만든 권한 요청은 직접 승인하거나 거절할 수 없습니다. requestId="
                + requestId + ", reviewerId=" + reviewerId);
    }
}
