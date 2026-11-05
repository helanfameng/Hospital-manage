package com.hospital.inpatient.common;

import java.time.Instant;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "操作成功", data, TraceIds.current(), Instant.now());
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("OK", message, data, TraceIds.current(), Instant.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceIds.current(), Instant.now());
    }
}

