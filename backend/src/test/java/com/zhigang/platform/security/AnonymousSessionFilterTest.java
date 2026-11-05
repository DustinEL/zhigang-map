package com.zhigang.platform.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymousSessionFilterTest {
    private final AnonymousSessionFilter filter = new AnonymousSessionFilter(false);

    @Test
    void createsHttpOnlySessionCookieAndReusesValidIdentifier() throws Exception {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("POST", "/api/resumes/text");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, new MockFilterChain());

        String owner = firstRequest.getAttribute(AnonymousSessionFilter.ATTRIBUTE).toString();
        assertThat(owner).matches("[a-f0-9-]{36}");
        assertThat(firstResponse.getHeader("Set-Cookie"))
                .contains("ZHIGANG_ANON=" + owner, "HttpOnly", "SameSite=Lax");

        MockHttpServletRequest nextRequest = new MockHttpServletRequest("GET", "/api/resumes/1001/facts");
        nextRequest.setCookies(new Cookie("ZHIGANG_ANON", owner));
        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        filter.doFilter(nextRequest, nextResponse, new MockFilterChain());

        assertThat(nextRequest.getAttribute(AnonymousSessionFilter.ATTRIBUTE)).isEqualTo(owner);
        assertThat(nextResponse.getHeader("Set-Cookie")).isNull();
    }
}
