package com.secureflow.secureflow.audit.ingest;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "secureflow.security-events.ingest")
public class SecurityEventIngestProperties {

    private boolean enabled;
    private String sourceId = "local-service";
    private String apiKey = "";

    @Min(1)
    @Max(500)
    private int maxBatchSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    @AssertTrue(message = "활성화된 이벤트 수집에는 source-id와 32자 이상의 api-key가 필요합니다.")
    public boolean isCredentialConfigurationValid() {
        if (!enabled) {
            return true;
        }
        return sourceId != null && sourceId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,49}")
                && apiKey != null && apiKey.length() >= 32;
    }
}
