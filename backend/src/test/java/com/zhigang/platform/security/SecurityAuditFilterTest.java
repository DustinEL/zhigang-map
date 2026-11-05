package com.zhigang.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAuditFilterTest {
    private final SecurityAuditFilter filter = new SecurityAuditFilter();

    @Test
    void assignsAuditIdToMutatingApiRequestsOnly() throws Exception {
        MockHttpServletRequest mutation = new MockHttpServletRequest("POST", "/api/change-proposals");
        MockHttpServletResponse mutationResponse = new MockHttpServletResponse();
        filter.doFilter(mutation, mutationResponse, new MockFilterChain());

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/job-roles");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        filter.doFilter(read, readResponse, new MockFilterChain());

        assertThat(mutationResponse.getHeader("X-Audit-Id")).matches("[a-f0-9-]{36}");
        assertThat(readResponse.getHeader("X-Audit-Id")).isNull();
    }
}
