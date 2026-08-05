package com.watchwise.watchwise_api.common.exception;

import java.time.LocalDateTime;
import java.util.List;

public record ValidationApiError(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ValidationError> errors
) {
}
