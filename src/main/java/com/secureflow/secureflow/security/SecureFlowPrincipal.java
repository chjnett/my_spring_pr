package com.secureflow.secureflow.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public record SecureFlowPrincipal(
        Long userId,
        String email,
        String passwordHash,
        Collection<? extends GrantedAuthority> authorities
) implements UserDetails {

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }
}
