package com.pfplaybackend.api.auth.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum AdminAuthException implements DomainException {
    INVALID_CREDENTIALS("AUTH_ADMIN_001", "이메일 또는 비밀번호가 올바르지 않습니다.", ErrorType.UNAUTHORIZED),
    RATE_LIMITED("AUTH_ADMIN_002", "너무 많은 요청입니다. 잠시 후 다시 시도해주세요.", ErrorType.TOO_MANY_REQUESTS),
    ACCOUNT_REVOKED("AUTH_ADMIN_003", "이메일 또는 비밀번호가 올바르지 않습니다.", ErrorType.UNAUTHORIZED);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminAuthException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
