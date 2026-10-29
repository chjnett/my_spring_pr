package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.GrantRoleCommand;
import com.secureflow.secureflow.authorization.application.RoleGrantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/role-grants")
public class RoleGrantController {

    private final RoleGrantService roleGrantService;

    public RoleGrantController(RoleGrantService roleGrantService) {
        this.roleGrantService = roleGrantService;
    }

    @PostMapping
    public ResponseEntity<RoleGrantResponse> grantRole(
            @PathVariable Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody GrantRoleRequest request
    ) {
        var result = roleGrantService.grantRoleIdempotently(
                userId,
                new GrantRoleCommand(request.role()),
                idempotencyKey
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(RoleGrantResponse.from(result.grantedRole()));
    }
}
