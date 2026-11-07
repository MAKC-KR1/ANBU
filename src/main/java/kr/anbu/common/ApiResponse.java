package kr.anbu.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ApiResponse<T>(boolean success, T data, ApiError error) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> failure(String code, String message) {
        return failure(code, message, null);
    }

    public static ApiResponse<Void> failure(String code, String message, Map<String, Object> details) {
        return new ApiResponse<>(false, null, new ApiError(code, message, details));
    }

    public record ApiError(String code, String message,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {}
}
