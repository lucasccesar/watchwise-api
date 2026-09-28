package com.watchwise.watchwise_api.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DailyGamesUnavailableException extends RuntimeException {

    public DailyGamesUnavailableException() {
        super("Daily games are temporarily unavailable");
    }
}
