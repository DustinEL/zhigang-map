package com.zhigang.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DefaultSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultConfigurationProtectsManagementEndpointsFromAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/public/overview"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/verification-submissions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/graph-releases"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/verification-submissions/1/reviews").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"VERIFIED","reason":"审核通过"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/data-imports").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
