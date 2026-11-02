package com.secureflow.secureflow.audit.ingest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
public class ServiceCredentialRegistry {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public ServiceCredentialRegistry(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void register(String credentialId, String sourceId, String secret, Instant expiresAt) {
        validate(credentialId, sourceId, secret, expiresAt);
        try {
            jdbcTemplate.update("""
                    insert into service_credentials (credential_id, source_id, secret_hash, expires_at)
                    values (?, ?, ?, ?)
                    """, credentialId, sourceId, passwordEncoder.encode(secret),
                    expiresAt == null ? null : Timestamp.from(expiresAt));
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("이미 존재하는 credentialId입니다.");
        }
    }

    @Transactional
    public void ensureBootstrapCredential(String credentialId, String sourceId, String secret) {
        validate(credentialId, sourceId, secret, null);
        jdbcTemplate.update("""
                insert into service_credentials (credential_id, source_id, secret_hash)
                values (?, ?, ?)
                on conflict (credential_id) do nothing
                """, credentialId, sourceId, passwordEncoder.encode(secret));
    }

    @Transactional
    public Optional<String> authenticate(String credentialId, String secret) {
        if (credentialId == null || secret == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        select source_id, secret_hash from service_credentials
                        where credential_id = ? and active = true
                          and (expires_at is null or expires_at > current_timestamp)
                        """, (rs, rowNum) -> new CredentialRow(rs.getString("source_id"), rs.getString("secret_hash")), credentialId)
                .stream().findFirst()
                .filter(row -> passwordEncoder.matches(secret, row.secretHash()))
                .map(row -> {
                    jdbcTemplate.update("update service_credentials set last_used_at = current_timestamp where credential_id = ?", credentialId);
                    return row.sourceId();
                });
    }

    @Transactional
    public boolean revoke(String credentialId) {
        return jdbcTemplate.update("""
                update service_credentials
                set active = false, revoked_at = current_timestamp
                where credential_id = ? and active = true
                """, credentialId) == 1;
    }

    private void validate(String credentialId, String sourceId, String secret, Instant expiresAt) {
        if (credentialId == null || !credentialId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) {
            throw new IllegalArgumentException("credentialId 형식이 올바르지 않습니다.");
        }
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) {
            throw new IllegalArgumentException("sourceId 형식이 올바르지 않습니다.");
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("secret은 32자 이상이어야 합니다.");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt은 미래 시각이어야 합니다.");
        }
    }

    private record CredentialRow(String sourceId, String secretHash) { }
}
