package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.AccessRequestResult;
import com.secureflow.secureflow.authorization.application.AccessRequestService;
import com.secureflow.secureflow.authorization.application.IdempotentAccessRequestResult;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.global.exception.SelfApprovalNotAllowedException;
import com.secureflow.secureflow.security.SecureFlowPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccessRequestController.class)
class AccessRequestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AccessRequestService accessRequestService;

    @Test
    void createsAccessRequestWithActorHeader() throws Exception {
        when(accessRequestService.createIdempotently(anyLong(), any(), eq("create-1")))
                .thenReturn(new IdempotentAccessRequestResult(
                        result(RoleGrantRequestStatus.PENDING, null), false
                ));

        mockMvc.perform(post("/api/access-requests")
                        .principal(actor(1L, "MEMBER"))
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":2,"role":"ADMIN","reason":"배포 작업"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requesterId").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(accessRequestService).createIdempotently(eq(1L), any(), eq("create-1"));
    }

    @Test
    void replayedCreateReturnsOriginal201WithReplayHeader() throws Exception {
        when(accessRequestService.createIdempotently(anyLong(), any(), eq("same-key")))
                .thenReturn(new IdempotentAccessRequestResult(
                        result(RoleGrantRequestStatus.PENDING, null), true
                ));

        mockMvc.perform(post("/api/access-requests")
                        .principal(actor(1L, "MEMBER"))
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUserId":2,"role":"ADMIN","reason":"배포 작업"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(accessRequestService).createIdempotently(eq(1L), any(), eq("same-key"));
    }

    @Test
    void approvesAccessRequest() throws Exception {
        when(accessRequestService.approve(eq(10L), anyLong(), eq("업무 확인")))
                .thenReturn(result(RoleGrantRequestStatus.APPROVED, 3L));

        mockMvc.perform(post("/api/access-requests/10/approve")
                        .principal(actor(3L, "AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"업무 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerId").value(3));
        verify(accessRequestService).approve(10L, 3L, "업무 확인");
    }

    @Test
    void selfApprovalReturns403() throws Exception {
        when(accessRequestService.approve(eq(10L), anyLong(), eq(null)))
                .thenThrow(new SelfApprovalNotAllowedException(10L, 1L));

        mockMvc.perform(post("/api/access-requests/10/approve")
                        .principal(actor(1L, "AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));
        verify(accessRequestService).approve(10L, 1L, null);
    }

    @Test
    void invalidCreateBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/access-requests")
                        .principal(actor(1L, "MEMBER"))
                        .header("Idempotency-Key", "invalid-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":-1,\"role\":null,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private AccessRequestResult result(RoleGrantRequestStatus status, Long reviewerId) {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        return new AccessRequestResult(
                10L, 1L, 2L, Role.ADMIN, "배포 작업", status,
                reviewerId, null, reviewerId == null ? null : now, 0L, now, now
        );
    }

    private UsernamePasswordAuthenticationToken actor(Long userId, String role) {
        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = new SecureFlowPrincipal(
                userId, "user" + userId + "@example.com", "password-hash", authorities
        );
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
