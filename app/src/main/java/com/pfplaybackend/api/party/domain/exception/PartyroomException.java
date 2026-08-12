package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum PartyroomException implements DomainException {
    NOT_FOUND_ROOM("PTR-001", "파티룸을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    ALREADY_TERMINATED("PTR-002", "이미 종료된 파티룸입니다", ErrorType.FORBIDDEN),
    EXCEEDED_LIMIT("PTR-003", "입장 인원 제한을 초과했습니다", ErrorType.FORBIDDEN),
    ACTIVE_ANOTHER_ROOM("PTR-004", "이미 다른 파티룸에 입장 중입니다", ErrorType.FORBIDDEN),
    RESTRICTED_AUTHORITY("PTR-005", "권한이 부족합니다", ErrorType.FORBIDDEN),
    ALREADY_HOST("PTR-006", "이미 다른 파티룸의 호스트입니다", ErrorType.FORBIDDEN),
    ILLEGAL_STATE_TRANSITION("PTR-007", "허용되지 않은 파티룸 상태 전이입니다", ErrorType.CONFLICT),
    LINK_DOMAIN_ALREADY_EXISTS("PTR-008", "이미 사용 중인 파티룸 주소입니다", ErrorType.CONFLICT),
    MAIN_STAGE_PROTECTED("PTR-009", "메인 스테이지는 보호된 시스템 파티룸입니다", ErrorType.CONFLICT),
    INVALID_NOTICE_CONTENT("PTR-010", "공지사항은 255자 이하여야 합니다", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    PartyroomException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
