package com.secureflow.secureflow.authorization.application;

public record IdempotentGrantedRole(GrantedRole grantedRole, boolean replayed) {
}
