package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum BugReportException implements DomainException {
    RATE_LIMIT_EXCEEDED("BUG-001", "잠시 후 다시 시도해주세요", ErrorType.TOO_MANY_REQUESTS),
    INVALID_LIST_QUERY("BUG-002", "유효하지 않은 목록 조회 조건입니다", ErrorType.BAD_REQUEST),
    BUG_REPORT_NOT_FOUND("BUG-003", "버그 리포트를 찾을 수 없습니다", ErrorType.NOT_FOUND);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    BugReportException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
