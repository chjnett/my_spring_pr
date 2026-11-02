package com.secureflow.secureflow.security;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import com.secureflow.secureflow.user.UserService;
import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapAdminService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final UserCredentialRepository credentialRepository;
    private final RoleGrantRepository roleGrantRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminService(
            UserRepository userRepository,
            UserService userService,
            UserCredentialRepository credentialRepository,
            RoleGrantRepository roleGrantRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.credentialRepository = credentialRepository;
        this.roleGrantRepository = roleGrantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse ensureAdmin(String name, String email, String password) {
        UserResponse admin = userRepository.findByEmail(email)
                .map(this::toResponse)
                .orElseGet(() -> userService.createUser(new CreateUserRequest(name, email, password)));

        credentialRepository.findById(admin.id()).ifPresentOrElse(
                credential -> {
                    if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
                        throw new IllegalStateException("초기 관리자 계정의 기존 비밀번호가 일치하지 않습니다.");
                    }
                },
                () -> credentialRepository.save(
                        new UserCredential(admin.id(), passwordEncoder.encode(password))
                )
        );
        if (!roleGrantRepository.existsByUserIdAndRole(admin.id(), Role.ADMIN)) {
            roleGrantRepository.save(new RoleGrant(admin.id(), Role.ADMIN));
        }
        return admin;
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
