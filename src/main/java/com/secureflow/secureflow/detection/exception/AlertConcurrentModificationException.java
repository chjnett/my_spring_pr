package com.secureflow.secureflow.detection.exception;

public class AlertConcurrentModificationException extends RuntimeException {
    public AlertConcurrentModificationException(Long alertId, Throwable cause) {
        super("다른 분석가가 먼저 경보를 변경했습니다: " + alertId, cause);
    }
}
