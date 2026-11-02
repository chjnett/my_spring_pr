package com.secureflow.secureflow.security;

import org.springframework.security.core.Authentication;

import java.security.Principal;

public final class SecurityActor {

    private SecurityActor() {
    }

    public static Long userId(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof SecureFlowPrincipal actor) {
            return actor.userId();
        }
        throw new IllegalStateException("인증 principal에서 사용자 ID를 확인할 수 없습니다.");
    }
}
