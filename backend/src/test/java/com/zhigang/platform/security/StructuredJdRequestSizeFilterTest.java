package com.zhigang.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredJdRequestSizeFilterTest {

    @Test
    void rejectsStructuredImportWhenDeclaredLengthExceedsLimit() throws Exception {
        StructuredJdRequestSizeFilter filter = new StructuredJdRequestSizeFilter(
                new ObjectMapper().findAndRegisterModules(), 8);
        MockHttpServletRequest request = importRequest(new byte[9]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("STRUCTURED_JD_REQUEST_TOO_LARGE");
        assertThat(invoked).isFalse();
    }

    @Test
    void allowsStructuredImportAtConfiguredLimit() throws Exception {
        StructuredJdRequestSizeFilter filter = new StructuredJdRequestSizeFilter(
                new ObjectMapper().findAndRegisterModules(), 8);
        MockHttpServletRequest request = importRequest(new byte[8]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (wrappedRequest, ignoredResponse) -> {
            wrappedRequest.getInputStream().readAllBytes();
            invoked.set(true);
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void stopsChunkedStructuredImportWhileReadingBeyondLimit() throws Exception {
        StructuredJdRequestSizeFilter filter = new StructuredJdRequestSizeFilter(
                new ObjectMapper().findAndRegisterModules(), 8);
        HttpServletRequest request = new HttpServletRequestWrapper(importRequest(new byte[9])) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (wrappedRequest, ignoredResponse) -> wrappedRequest.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("STRUCTURED_JD_REQUEST_TOO_LARGE");
    }

    private MockHttpServletRequest importRequest(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                StructuredJdRequestSizeFilter.IMPORT_PATH);
        request.setContentType("application/json");
        request.setContent(content);
        return request;
    }
}
