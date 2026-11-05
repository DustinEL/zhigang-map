package com.zhigang.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zhigang.security.dev-permit-all=false",
        "zhigang.security.professional-username=reviewer",
        "zhigang.security.professional-password=test-password",
        "zhigang.security.reviewer-username=approver",
        "zhigang.security.reviewer-password=second-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ProfessionalSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void keepsRuntimeStatusPublicButProtectsProfessionalQueues() throws Exception {
        mockMvc.perform(get("/api/runtime-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataMode").value("DEMO"));

        mockMvc.perform(get("/api/evaluations/status"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/current-release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("2026.07"));

        mockMvc.perform(get("/api/admin/graph-releases"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/review-queues/summary"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/professional/source-ledger"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/job-roles/2/evolution-runs/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_EVOLUTION_RUN_NOT_FOUND"));

        mockMvc.perform(post("/api/job-roles/2/evolution-runs").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/verification-submissions/1/reviews").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECTED","reason":"证据与技能无关"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/verification-submissions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/role-evolution-runs/1/draft").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = {"PROFESSIONAL", "EDITOR"})
    void rejectsProfessionalMutationWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/dynamic-records/retriage"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsProfessionalSessionWithConfiguredAccount() throws Exception {
        mockMvc.perform(post("/api/security/professional/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"reviewer","password":"test-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.username").value("reviewer"))
                .andExpect(jsonPath("$.data.roles[?(@ == 'EDITOR')]").exists())
                .andExpect(jsonPath("$.data.capabilities[?(@ == 'PUBLISH_PROMOTION_BATCH')]").exists())
                .andExpect(jsonPath("$.data.capabilities[?(@ == 'MANAGE_ROLE_EVOLUTION')]").exists());

        mockMvc.perform(post("/api/security/professional/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"approver","password":"second-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("approver"))
                .andExpect(jsonPath("$.data.roles[?(@ == 'REVIEWER')]").exists())
                .andExpect(jsonPath("$.data.capabilities[0]").value("REVIEW_PROMOTION_BATCH"));
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "PROFESSIONAL")
    void allowsProfessionalAccountToReadReviewQueue() throws Exception {
        mockMvc.perform(get("/api/review-queues/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRecords").value(0));

        mockMvc.perform(get("/api/professional/source-ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/admin/graph-releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = "PROFESSIONAL")
    void allowsProfessionalAccountToReachVerificationReview() throws Exception {
        mockMvc.perform(get("/api/admin/verification-submissions")
                        .param("status", "PENDING_VERIFICATION")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").isNumber());

        mockMvc.perform(post("/api/admin/verification-submissions/999999/reviews").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECTED","reason":"证据与技能无关"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "approver", roles = {"PROFESSIONAL", "REVIEWER"})
    void reviewerCannotRunEditorOnlyTriage() throws Exception {
        mockMvc.perform(post("/api/dynamic-records/retriage").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/job-roles/2/evolution-runs").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/role-evolution-runs/1/publications").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "reviewer", roles = {"PROFESSIONAL", "EDITOR"})
    void editorCanRunTriage() throws Exception {
        mockMvc.perform(post("/api/dynamic-records/retriage").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void blocksRepeatedFailedLogins() throws Exception {
        String body = """
                {"username":"rate-limit-user","password":"wrong-password"}
                """;
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/security/professional/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/security/professional/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }
}
