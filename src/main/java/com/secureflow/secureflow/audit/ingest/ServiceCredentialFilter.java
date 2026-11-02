package com.secureflow.secureflow.audit.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ServiceCredentialFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-SecureFlow-Ingest-Key";
    public static final String PATH = "/api/internal/security-events:batch";

    private final SecurityEventIngestProperties properties;

    public ServiceCredentialFilter(SecurityEventIngestProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String applicationPath = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !PATH.equals(applicationPath);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SECURITY_EVENT_INGEST_DISABLED", "보안 이벤트 수집 API가 비활성화되어 있습니다.");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (!matches(provided, properties.getApiKey())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_SERVICE_CREDENTIAL", "유효한 서비스 자격 증명이 필요합니다.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
