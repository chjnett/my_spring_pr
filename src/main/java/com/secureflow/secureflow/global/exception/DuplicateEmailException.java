package com.secureflow.secureflow.global.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("이미 사용 중인 이메일입니다: " + email);
    }

    public DuplicateEmailException(String email, Throwable cause) {
        super("이미 사용 중인 이메일입니다: " + email, cause);
    }
}
