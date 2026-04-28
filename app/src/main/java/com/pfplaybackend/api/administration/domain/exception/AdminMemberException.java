package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * Member 어드민 도메인 예외.
 * Codes: MBR-NNN. Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §8.2
 */
@Getter
public enum AdminMemberException implements DomainException {

    MEMBER_NOT_FOUND("MBR-001", "Member 가 존재하지 않습니다.", ErrorType.NOT_FOUND);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminMemberException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
