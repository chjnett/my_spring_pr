package com.secureflow.secureflow.authorization.application;

/**
 * authorization 모듈이 user 모듈에 요구하는 최소 계약이다.
 * 나중에 user 서비스가 분리되면 HTTP/gRPC 구현으로 교체할 수 있다.
 */
public interface UserExistencePort {
    boolean exists(Long userId);
}
