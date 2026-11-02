package com.secureflow.secureflow.authorization.application;

public record IdempotentAccessRequestResult(AccessRequestResult accessRequest, boolean replayed) {
}
