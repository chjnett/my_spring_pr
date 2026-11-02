package com.secureflow.secureflow.audit.ingest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecurityEventIngestController {

    private final SecurityEventIngestService ingestService;

    public SecurityEventIngestController(SecurityEventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(ServiceCredentialFilter.PATH)
    public ResponseEntity<SecurityEventBatchResponse> ingest(
            @Valid @RequestBody SecurityEventBatchRequest request
    ) {
        SecurityEventBatchResponse response = ingestService.ingest(request);
        return ResponseEntity.accepted().body(response);
    }
}
