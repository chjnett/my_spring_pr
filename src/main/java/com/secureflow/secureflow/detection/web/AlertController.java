package com.secureflow.secureflow.detection.web;

import com.secureflow.secureflow.detection.application.AlertService;
import com.secureflow.secureflow.detection.domain.AlertStatus;
import com.secureflow.secureflow.security.SecurityActor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<AlertResponse> list(@RequestParam(required = false) AlertStatus status) {
        return alertService.list(status).stream().map(AlertResponse::from).toList();
    }

    @GetMapping("/{alertId}")
    public AlertResponse get(@PathVariable Long alertId) {
        return AlertResponse.from(alertService.get(alertId));
    }

    @PostMapping("/{alertId}/assign")
    public AlertResponse assign(
            @PathVariable Long alertId,
            Principal principal,
            @Valid @RequestBody AssignAlertRequest request
    ) {
        return AlertResponse.from(alertService.assign(
                alertId, request.assigneeId(), SecurityActor.userId(principal)
        ));
    }

    @PostMapping("/{alertId}/resolve")
    public AlertResponse resolve(
            @PathVariable Long alertId,
            Principal principal,
            @Valid @RequestBody ResolveAlertRequest request
    ) {
        return AlertResponse.from(alertService.resolve(
                alertId, request.resolutionNote(), SecurityActor.userId(principal)
        ));
    }
}
