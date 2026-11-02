package com.secureflow.secureflow.security;

import com.secureflow.secureflow.authorization.persistence.RoleGrantRequestRepository;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.user.UserService;
import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityAuthenticationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String REQUESTER_PASSWORD = "requester-password-123";
    private static final String REVIEWER_PASSWORD = "reviewer-password-123";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired UserCredentialRepository credentialRepository;
    @Autowired RoleGrantRequestRepository requestRepository;
    @Autowired RoleGrantRepository roleGrantRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BootstrapAdminService bootstrapAdminService;

    @Test
    void authenticatesFromDatabaseAndAuthorizesReviewByRole() throws Exception {
        UserResponse requester = userService.createUser(new CreateUserRequest(
                "requester", "requester-security@example.com", REQUESTER_PASSWORD
        ));
        UserResponse target = userService.createUser(new CreateUserRequest(
                "target", "target-security@example.com", "target-password-123"
        ));
        UserResponse reviewer = bootstrapAdminService.ensureAdmin(
                "reviewer", "reviewer-security@example.com", REVIEWER_PASSWORD
        );
        UserResponse existingMember = userService.createUser(new CreateUserRequest(
                "existing", "existing-security@example.com", "existing-password-123"
        ));

        assertThatThrownBy(() -> bootstrapAdminService.ensureAdmin(
                "existing", existingMember.email(), "different-password-123"
        )).isInstanceOf(IllegalStateException.class);
        assertThat(roleGrantRepository.existsByUserIdAndRole(existingMember.id(), Role.ADMIN)).isFalse();

        UserCredential credential = credentialRepository.findById(requester.id()).orElseThrow();
        assertThat(credential.getPasswordHash()).isNotEqualTo(REQUESTER_PASSWORD);
        assertThat(passwordEncoder.matches(REQUESTER_PASSWORD, credential.getPasswordHash())).isTrue();

        String body = """
                {"targetUserId":%d,"role":"AUDITOR","reason":"보안 이벤트 검토"}
                """.formatted(target.id());

        mockMvc.perform(post("/api/access-requests")
                        .header("Idempotency-Key", "security-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(requester.email(), "wrong-password"))
                        .header("Idempotency-Key", "security-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/access-requests")
                        .with(httpBasic(requester.email(), REQUESTER_PASSWORD))
                        .header("Idempotency-Key", "security-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requesterId").value(requester.id()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        Long requestId = requestRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/access-requests/{id}/approve", requestId)
                        .with(httpBasic(requester.email(), REQUESTER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"self review\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/access-requests/{id}/approve", requestId)
                        .with(httpBasic(reviewer.email(), REVIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"업무 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerId").value(reviewer.id()));
    }
}
