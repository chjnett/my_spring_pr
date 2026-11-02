package com.secureflow.secureflow.detection.domain;

public enum DetectionRule {
    ACTOR_LOGIN_FAILURE_BURST(AlertSeverity.HIGH, "ACTOR"),
    IP_MULTI_ACCOUNT_LOGIN_FAILURE(AlertSeverity.CRITICAL, "IP"),
    ACTOR_ACCESS_DENIED_BURST(AlertSeverity.MEDIUM, "ACTOR"),
    ACTOR_PRIVILEGED_ACTION_FAILURE(AlertSeverity.CRITICAL, "ACTOR");

    private final AlertSeverity severity;
    private final String subjectType;

    DetectionRule(AlertSeverity severity, String subjectType) {
        this.severity = severity;
        this.subjectType = subjectType;
    }

    public AlertSeverity severity() {
        return severity;
    }

    public String subjectType() {
        return subjectType;
    }
}
