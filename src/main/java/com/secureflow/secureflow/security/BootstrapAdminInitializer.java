package com.secureflow.secureflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final BootstrapAdminService bootstrapAdminService;
    private final String name;
    private final String email;
    private final String password;

    public BootstrapAdminInitializer(
            BootstrapAdminService bootstrapAdminService,
            @Value("${secureflow.bootstrap-admin.name:SecureFlow Admin}") String name,
            @Value("${secureflow.bootstrap-admin.email:}") String email,
            @Value("${secureflow.bootstrap-admin.password:}") String password
    ) {
        this.bootstrapAdminService = bootstrapAdminService;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("초기 관리자 email과 password는 함께 설정해야 합니다.");
        }
        if (password.length() < 12 || password.length() > 72) {
            throw new IllegalStateException("초기 관리자 password는 12자 이상 72자 이하여야 합니다.");
        }
        bootstrapAdminService.ensureAdmin(name, email, password);
    }
}
