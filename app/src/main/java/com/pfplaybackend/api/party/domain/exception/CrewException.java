package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum CrewException implements DomainException {
    NOT_FOUND_ACTIVE_ROOM("CRW-001", "참여 중인 파티룸을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    INVALID_ACTIVE_ROOM("CRW-002", "유효하지 않은 파티룸 참여 상태입니다", ErrorType.CONFLICT),
    NOT_FOUND_ROOM("CRW-003", "파티룸의 크루가 아닙니다", ErrorType.NOT_FOUND),
    PROFILE_REQUIRED("CRW-004", "프로필 등록이 완료되어야 파티룸에 참여할 수 있습니다", ErrorType.FORBIDDEN);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    CrewException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
