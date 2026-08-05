package com.watchwise.watchwise_api.common.exception;

public record ValidationError(
        String field,
        String message
) {
}
