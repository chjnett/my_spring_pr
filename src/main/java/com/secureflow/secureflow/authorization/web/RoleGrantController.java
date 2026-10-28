package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.GrantRoleCommand;
import com.secureflow.secureflow.authorization.application.RoleGrantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/role-grants")
public class RoleGrantController {

    private final RoleGrantService roleGrantService;

    public RoleGrantController(RoleGrantService roleGrantService) {
        this.roleGrantService = roleGrantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleGrantResponse grantRole(
            @PathVariable Long userId,
            @Valid @RequestBody GrantRoleRequest request
    ) {
        return RoleGrantResponse.from(
                roleGrantService.grantRole(userId, new GrantRoleCommand(request.role()))
        );
    }
}
