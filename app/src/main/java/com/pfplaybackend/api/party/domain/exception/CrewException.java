package com.pfplaybackend.api.party.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum CrewException implements DomainException {
    NOT_FOUND_ACTIVE_ROOM("CRW-001", "참여 중인 파티룸을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    INVALID_ACTIVE_ROOM("CRW-002", "유효하지 않은 파티룸 참여 상태입니다", ErrorType.CONFLICT),
    NOT_FOUND_ROOM("CRW-003", "파티룸의 크루가 아닙니다", ErrorType.NOT_FOUND),
    PROFILE_REQUIRED("CRW-004", "프로필 등록이 완료되어야 파티룸에 참여할 수 있습니다", ErrorType.FORBIDDEN),
    // #349 동시 입장 경쟁 패자: 다른 방 입장이 이 유저를 먼저 active 로 만들어(uk_crew_active_user)
    // 본 입장이 거부됨. "유저당 활성 방 1개" DB 불변식이 다중 활성을 조용히 허용하던 자리를 대체한다.
    CONCURRENT_ACTIVE_ROOM("CRW-005", "다른 파티룸 입장이 처리 중입니다. 잠시 후 다시 시도해 주세요", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    CrewException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
