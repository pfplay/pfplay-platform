package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum DjException implements DomainException {
    ALREADY_REGISTERED("DJ-001", "이미 DJ로 등록되어 있습니다", ErrorType.CONFLICT),
    QUEUE_CLOSED("DJ-002", "DJ 대기열이 닫혀 있습니다", ErrorType.FORBIDDEN),
    EMPTY_PLAYLIST("DJ-003", "빈 플레이리스트로 등록할 수 없습니다", ErrorType.FORBIDDEN),
    NOT_FOUND_DJ("DJ-004", "DJ 대기열에서 해당 DJ를 찾을 수 없습니다", ErrorType.NOT_FOUND),
    NOT_OWNED_PLAYLIST("DJ-005", "본인 소유 플레이리스트가 아닙니다", ErrorType.FORBIDDEN),
    CURRENT_DJ_CANNOT_CHANGE_PLAYLIST("DJ-006", "재생 중 DJ는 플레이리스트를 변경할 수 없습니다", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    DjException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
