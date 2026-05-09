package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * PartyroomReport 도메인 예외.
 * Codes: RPT-NNN. Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr13-design.md
 */
@Getter
public enum AdminReportException implements DomainException {

    REPORT_NOT_FOUND("RPT-001", "신고가 존재하지 않습니다.", ErrorType.NOT_FOUND),
    INVALID_STATE_TRANSITION("RPT-002", "허용되지 않는 신고 상태 전이입니다.", ErrorType.BAD_REQUEST),
    RESOLUTION_NOTE_REQUIRED("RPT-003", "처리 완료(RESOLVED/DISMISSED) 시 처리 메모가 필요합니다.", ErrorType.BAD_REQUEST),
    INVALID_LIST_QUERY("RPT-004", "신고 목록 조회 query 파라미터가 유효하지 않습니다.", ErrorType.BAD_REQUEST),
    PARTYROOM_NOT_REPORTABLE("RPT-005", "신고할 수 없는 파티룸 상태입니다.", ErrorType.BAD_REQUEST),
    SELF_REPORT_FORBIDDEN("RPT-006", "본인이 호스트인 파티룸은 신고할 수 없습니다.", ErrorType.BAD_REQUEST),
    DUPLICATE_REPORT("RPT-007", "최근 24시간 내 동일 카테고리로 이미 신고하였습니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminReportException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
