package com.secureflow.secureflow.audit.ingest;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IngestCredentialBootstrap {

    @Bean
    ApplicationRunner ingestCredentialBootstrapRunner(
            SecurityEventIngestProperties properties,
            ServiceCredentialRegistry registry
    ) {
        return arguments -> {
            if (properties.isEnabled()) {
                registry.ensureBootstrapCredential(
                        properties.getCredentialId(), properties.getSourceId(), properties.getApiKey()
                );
            }
        };
    }
}
