package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * SystemAnnouncement 도메인 예외.
 * Codes: ANN-NNN. Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md
 */
@Getter
public enum AnnouncementException implements DomainException {

    ANNOUNCEMENT_NOT_FOUND("ANN-001", "공지를 찾을 수 없습니다.", ErrorType.NOT_FOUND),
    ALREADY_CANCELLED("ANN-002", "이미 철회된 공지입니다.", ErrorType.CONFLICT),
    INVALID_SCHEDULE_FOR_TYPE("ANN-003", "공지 타입과 일정 정보가 일치하지 않습니다.", ErrorType.BAD_REQUEST),
    INVALID_SCHEDULE_WINDOW("ANN-004", "예약 종료 시각은 시작 시각보다 이후여야 합니다.", ErrorType.BAD_REQUEST),
    SCHEDULED_START_IN_PAST("ANN-005", "예약 시작 시각은 미래여야 합니다.", ErrorType.BAD_REQUEST),
    INVALID_END_ADJUSTMENT("ANN-006", "조정할 종료 시각은 현재 이후여야 합니다.", ErrorType.BAD_REQUEST),
    NOT_ACTIVE_MAINTENANCE("ANN-007", "진행 중인 점검 공지가 아닙니다.", ErrorType.CONFLICT),
    ALREADY_COMPLETED("ANN-008", "이미 정상 종료된 점검 공지입니다.", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AnnouncementException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
