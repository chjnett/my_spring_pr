package com.secureflow.secureflow.authorization.web;

import com.secureflow.secureflow.authorization.application.GrantedRole;
import com.secureflow.secureflow.authorization.application.RoleGrantService;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.global.exception.DuplicateRoleGrantException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoleGrantController.class)
class RoleGrantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleGrantService roleGrantService;

    @Test
    void returns201WhenRoleIsGranted() throws Exception {
        when(roleGrantService.grantRole(any(), any()))
                .thenReturn(new GrantedRole(1L, 10L, Role.ADMIN));

        mockMvc.perform(post("/api/users/10/role-grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void returns409WhenRoleWasAlreadyGranted() throws Exception {
        when(roleGrantService.grantRole(any(), any()))
                .thenThrow(new DuplicateRoleGrantException(10L, Role.ADMIN));

        mockMvc.perform(post("/api/users/10/role-grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ROLE_GRANT"));
    }
}
