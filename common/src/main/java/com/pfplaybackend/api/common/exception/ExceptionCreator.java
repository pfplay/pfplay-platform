package com.pfplaybackend.api.common.exception;

import com.pfplaybackend.api.common.exception.http.*;

public class ExceptionCreator {

    private ExceptionCreator() {}

    public static AbstractHTTPException create(DomainException domainException) {
        String errorCode = domainException.getErrorCode();
        String message = domainException.getMessage();
        return switch (domainException.getErrorType()) {
            case BAD_REQUEST -> new BadRequestException(errorCode, message);
            case UNAUTHORIZED -> new UnauthorizedException(errorCode, message);
            case FORBIDDEN -> new ForbiddenException(errorCode, message);
            case NOT_FOUND -> new NotFoundException(errorCode, message);
            case CONFLICT -> new ConflictException(errorCode, message);
            case TOO_MANY_REQUESTS -> new TooManyRequestsException(errorCode, message);
        };
    }
}
