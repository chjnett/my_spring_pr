package com.secureflow.secureflow.audit.query;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/security-events")
public class SecurityEventQueryController {

    private final SecurityEventQueryService queryService;

    public SecurityEventQueryController(SecurityEventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public SecurityEventPage search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String sourceIp,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor
    ) {
        return queryService.search(from, to, eventType, actorId, sourceIp, outcome, size, cursor);
    }
}
