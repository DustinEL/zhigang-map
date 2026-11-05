package com.zhigang.platform.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

final class RetryPolicy {
    private RetryPolicy() {
    }

    static <T> T execute(CheckedSupplier<T> operation, int maxAttempts,
                         Duration initialDelay, Consumer<Duration> sleeper) throws Exception {
        Objects.requireNonNull(operation);
        int attempts = Math.max(1, maxAttempts);
        Duration delay = initialDelay == null || initialDelay.isNegative() ? Duration.ZERO : initialDelay;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return operation.get();
            } catch (Exception exception) {
                last = exception;
                if (attempt == attempts) break;
                Duration currentDelay = delay.multipliedBy(1L << Math.min(attempt - 1, 20));
                sleeper.accept(currentDelay);
            }
        }
        throw last;
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
