package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum PenaltyException implements DomainException {
    PERMANENT_EXPULSION("PNT-001", "이용이 정지된 사용자입니다", ErrorType.FORBIDDEN),
    PENALTY_HISTORY_NOT_FOUND("PNT-002", "페널티 이력을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    ADMIN_APPLIED_PENALTY_REQUIRES_ADMIN_RELEASE(
            "PNT-003",
            "어드민이 부과한 페널티는 어드민만 해제할 수 있습니다",
            ErrorType.FORBIDDEN),
    CREW_APPLIED_PENALTY_NOT_ADMIN_RELEASABLE(
            "PNT-004",
            "크루가 부과한 페널티는 어드민이 해제할 수 없습니다",
            ErrorType.FORBIDDEN);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    PenaltyException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
