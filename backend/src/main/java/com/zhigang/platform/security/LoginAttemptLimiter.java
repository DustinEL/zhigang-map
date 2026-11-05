package com.zhigang.platform.security;

import com.zhigang.platform.common.RateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptLimiter {
    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;

    public LoginAttemptLimiter(
            @Value("${zhigang.security.login-max-failures:5}") int maxFailures,
            @Value("${zhigang.security.login-window-minutes:15}") long windowMinutes
    ) {
        this.maxFailures = Math.max(1, maxFailures);
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    }

    public void check(String key) {
        AttemptWindow attempt = attempts.get(key);
        if (attempt == null) return;
        Instant now = Instant.now();
        if (attempt.expiresAt().isBefore(now)) {
            attempts.remove(key, attempt);
            return;
        }
        if (attempt.failures() >= maxFailures) {
            throw new RateLimitException("登录失败次数过多，请稍后再试");
        }
    }

    public void failed(String key) {
        Instant now = Instant.now();
        attempts.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new AttemptWindow(1, now.plus(window));
            }
            return new AttemptWindow(current.failures() + 1, current.expiresAt());
        });
        check(key);
    }

    public void succeeded(String key) {
        attempts.remove(key);
    }

    private record AttemptWindow(int failures, Instant expiresAt) {
    }
}
