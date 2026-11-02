package com.secureflow.secureflow.detection.exception;

public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(Long id) {
        super("경보를 찾을 수 없습니다: " + id);
    }
}
