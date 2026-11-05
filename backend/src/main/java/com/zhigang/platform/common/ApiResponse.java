package com.zhigang.platform.common;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApiResponse<T>(
        String traceId,
        String code,
        String message,
        T data,
        OffsetDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(UUID.randomUUID().toString(), "OK", "操作成功", data, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(UUID.randomUUID().toString(), code, message, null, OffsetDateTime.now());
    }
}
