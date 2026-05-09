package com.pfplaybackend.api.common.exception.http;

import org.springframework.http.HttpStatus;

public class TooManyRequestsException extends AbstractHTTPException {
    public TooManyRequestsException(String errorCode, String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, errorCode, message);
    }
}
