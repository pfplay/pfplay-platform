package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * Guest 어드민 도메인 예외. Codes: GST-NNN.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §9.1.
 * MEMBER 와 동형 패턴(error code prefix 만 GST). MUTATION 부재 (read-only) 라
 * NOT_FOUND + INVALID_LIST_QUERY 두 코드만.
 */
@Getter
public enum AdminGuestException implements DomainException {

    GUEST_NOT_FOUND("GST-001", "Guest 가 존재하지 않습니다.", ErrorType.NOT_FOUND),
    INVALID_LIST_QUERY("GST-002", "Guest 목록 조회 query 파라미터가 유효하지 않습니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminGuestException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
