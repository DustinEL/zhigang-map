package com.zhigang.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhigang.platform.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
public class StructuredJdRequestSizeFilter extends OncePerRequestFilter {
    static final String IMPORT_PATH = "/api/data-imports/structured-jd";

    private final ObjectMapper objectMapper;
    private final long maxRequestBytes;

    public StructuredJdRequestSizeFilter(
            ObjectMapper objectMapper,
            @Value("${zhigang.structured-jd.max-request-bytes:31457280}") long maxRequestBytes
    ) {
        this.objectMapper = objectMapper;
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !IMPORT_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            writeTooLarge(response);
            return;
        }
        try {
            filterChain.doFilter(new SizeLimitedRequest(request, maxRequestBytes), response);
        } catch (RequestBodyTooLargeException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            writeTooLarge(response);
        }
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                "STRUCTURED_JD_REQUEST_TOO_LARGE", "结构化 JD 文件不能超过 30MB"));
    }

    public static final class RequestBodyTooLargeException extends IOException {
        RequestBodyTooLargeException() {
            super("Structured JD request body exceeds the configured limit");
        }
    }

    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {
        private final long maxRequestBytes;

        private SizeLimitedRequest(HttpServletRequest request, long maxRequestBytes) {
            super(request);
            this.maxRequestBytes = maxRequestBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new SizeLimitedInputStream(super.getInputStream(), maxRequestBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }

    private static final class SizeLimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxRequestBytes;
        private long bytesRead;

        private SizeLimitedInputStream(ServletInputStream delegate, long maxRequestBytes) {
            this.delegate = delegate;
            this.maxRequestBytes = maxRequestBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) increment(count);
            return count;
        }

        private void increment(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maxRequestBytes) throw new RequestBodyTooLargeException();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
