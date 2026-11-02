package com.secureflow.secureflow.security;

import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecureFlowUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final RoleGrantRepository roleGrantRepository;

    public SecureFlowUserDetailsService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            RoleGrantRepository roleGrantRepository
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.roleGrantRepository = roleGrantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("인증 사용자를 찾을 수 없습니다."));
        UserCredential credential = credentialRepository.findById(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("등록된 자격 증명이 없습니다."));
        var authorities = roleGrantRepository.findAllByUserId(user.getId()).stream()
                .map(grant -> new SimpleGrantedAuthority("ROLE_" + grant.getRole().name()))
                .toList();

        return new SecureFlowPrincipal(
                user.getId(), user.getEmail(), credential.getPasswordHash(), authorities
        );
    }
}
