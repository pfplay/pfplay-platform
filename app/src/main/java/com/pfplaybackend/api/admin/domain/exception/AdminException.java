package com.pfplaybackend.api.admin.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum AdminException implements DomainException {
    NON_VIRTUAL_MEMBER_AVATAR_UPDATE("ADM-001", "가상 회원이 아닌 사용자의 아바타를 수정할 수 없습니다", ErrorType.FORBIDDEN),
    NON_VIRTUAL_MEMBER_DELETE("ADM-002", "가상 회원이 아닌 사용자를 삭제할 수 없습니다", ErrorType.FORBIDDEN),
    NOT_VIRTUAL_MEMBER("ADM-003", "가상 회원이 아닙니다", ErrorType.FORBIDDEN),
    MEMBER_NOT_FOUND("ADM-004", "회원을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    INVALID_USER_ID_FORMAT("ADM-005", "유효하지 않은 사용자 ID 형식입니다", ErrorType.BAD_REQUEST),
    PARTYROOM_NOT_FOUND("ADM-006", "파티룸을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    NO_ACTIVE_PLAYBACK("ADM-007", "파티룸에 활성 재생이 없습니다", ErrorType.NOT_FOUND),
    NO_AVAILABLE_CREW("ADM-008", "사용 가능한 크루가 없습니다", ErrorType.NOT_FOUND),
    REACTION_SIMULATION_FAILED("ADM-009", "리액션 시뮬레이션에 실패했습니다", ErrorType.BAD_REQUEST),
    AVATAR_RESOURCES_NOT_INITIALIZED("ADM-010", "아바타 리소스가 초기화되지 않았습니다", ErrorType.BAD_REQUEST),
    MAIN_STAGE_NOT_FOUND("ADM-011", "메인 스테이지를 찾을 수 없습니다", ErrorType.NOT_FOUND),
    NO_CREW_MEMBERS("ADM-012", "파티룸에 크루가 없습니다", ErrorType.NOT_FOUND),
    NON_VIRTUAL_MEMBER_NICKNAME_UPDATE("ADM-013", "가상 회원이 아닌 사용자의 닉네임을 수정할 수 없습니다", ErrorType.FORBIDDEN),
    DUPLICATE_NICKNAME("ADM-014", "이미 사용 중인 닉네임입니다", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminException(String errorCode, String message, ErrorType errorType) {
        this.message = message;
        this.errorCode = errorCode;
        this.errorType = errorType;
    }
}
