package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.AccessRequestService;
import com.secureflow.secureflow.authorization.application.CreateAccessRequestCommand;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.security.SecurityActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/access-requests")
public class AccessRequestController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final AccessRequestService accessRequestService;

    public AccessRequestController(AccessRequestService accessRequestService) {
        this.accessRequestService = accessRequestService;
    }

    @PostMapping
    public ResponseEntity<AccessRequestResponse> create(
            Principal principal,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateAccessRequest request
    ) {
        var result = accessRequestService.createIdempotently(
                SecurityActor.userId(principal),
                new CreateAccessRequestCommand(request.targetUserId(), request.role(), request.reason()),
                idempotencyKey
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(AccessRequestResponse.from(result.accessRequest()));
    }

    @PostMapping("/{requestId}/approve")
    public AccessRequestResponse approve(
            @PathVariable Long requestId,
            Principal principal,
            @Valid @RequestBody ReviewAccessRequest request
    ) {
        return AccessRequestResponse.from(accessRequestService.approve(
                requestId, SecurityActor.userId(principal), request.reason()
        ));
    }

    @PostMapping("/{requestId}/reject")
    public AccessRequestResponse reject(
            @PathVariable Long requestId,
            Principal principal,
            @Valid @RequestBody RejectAccessRequest request
    ) {
        return AccessRequestResponse.from(accessRequestService.reject(
                requestId, SecurityActor.userId(principal), request.reason()
        ));
    }

    @GetMapping("/{requestId}")
    public AccessRequestResponse get(@PathVariable Long requestId) {
        return AccessRequestResponse.from(accessRequestService.get(requestId));
    }

    @GetMapping
    public List<AccessRequestResponse> list(@RequestParam(required = false) RoleGrantRequestStatus status) {
        return accessRequestService.list(status).stream().map(AccessRequestResponse::from).toList();
    }

}
