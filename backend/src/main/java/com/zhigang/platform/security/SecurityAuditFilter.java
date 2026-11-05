package com.zhigang.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityAuditFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAuditFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String auditId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        response.setHeader("X-Audit-Id", auditId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOGGER.info("auditId={} method={} path={} status={} durationMs={} actor={}", auditId,
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, actor(request));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || !MUTATING_METHODS.contains(request.getMethod());
    }

    private String actor(HttpServletRequest request) {
        return request.getUserPrincipal() == null ? "ANONYMOUS_SESSION" : request.getUserPrincipal().getName();
    }
}
