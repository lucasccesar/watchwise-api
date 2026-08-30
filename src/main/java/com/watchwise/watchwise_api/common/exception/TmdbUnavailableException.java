package com.watchwise.watchwise_api.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class TmdbUnavailableException extends RuntimeException {
    public TmdbUnavailableException(String message) {
        super(message);
    }
}
