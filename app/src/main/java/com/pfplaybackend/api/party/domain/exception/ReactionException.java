package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum ReactionException implements DomainException {
    INVALID_REACTION("RCT-001", "유효하지 않은 리액션 타입입니다", ErrorType.FORBIDDEN),
    REACTION_CONFLICT_RETRY("RCT-002", "동시 요청 충돌 — 잠시 후 다시 시도해 주세요", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    ReactionException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
