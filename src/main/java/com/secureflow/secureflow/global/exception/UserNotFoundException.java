package com.secureflow.secureflow.global.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long userId) {
        super("사용자를 찾을 수 없습니다. id=" + userId);
    }
}
