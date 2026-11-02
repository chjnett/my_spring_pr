package com.secureflow.secureflow.audit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "secureflow.audit.integrity")
public class AuditIntegrityProperties {

    private String keyId;
    private String secret;
    private Map<String, String> verificationKeys = new LinkedHashMap<>();

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Map<String, String> getVerificationKeys() {
        return verificationKeys;
    }

    public void setVerificationKeys(Map<String, String> verificationKeys) {
        this.verificationKeys = verificationKeys == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(verificationKeys);
    }
}
