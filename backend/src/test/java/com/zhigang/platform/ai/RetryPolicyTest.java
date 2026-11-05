package com.zhigang.platform.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {
    @Test
    void retriesUntilSuccessAndUsesExponentialDelays() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Duration> delays = new ArrayList<>();

        String result = RetryPolicy.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("temporary");
            return "ok";
        }, 3, Duration.ofMillis(10), delays::add);

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofMillis(10), Duration.ofMillis(20)), delays);
    }

    @Test
    void stopsAfterConfiguredAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> RetryPolicy.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("failed");
        }, 2, Duration.ZERO, ignored -> { }));

        assertEquals(2, attempts.get());
    }
}
