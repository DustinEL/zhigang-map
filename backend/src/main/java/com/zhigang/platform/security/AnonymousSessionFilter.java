package com.zhigang.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class AnonymousSessionFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = AnonymousSessionFilter.class.getName() + ".owner";
    private static final String COOKIE = "ZHIGANG_ANON";
    private static final Pattern SAFE_ID = Pattern.compile("[a-f0-9-]{36}");
    private final boolean secureCookie;

    public AnonymousSessionFilter(@Value("${zhigang.security.secure-cookie:false}") boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String owner = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> SAFE_ID.matcher(value).matches())
                .findFirst().orElse(null);
        if (owner == null) {
            owner = UUID.randomUUID().toString();
            String cookie = COOKIE + "=" + owner + "; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax" + (secureCookie ? "; Secure" : "");
            response.addHeader("Set-Cookie", cookie);
        }
        request.setAttribute(ATTRIBUTE, owner);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
