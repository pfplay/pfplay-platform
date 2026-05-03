package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum AdministratorManagementException implements DomainException {
    NOT_FOUND("ADM_MGT_001", "어드민을 찾을 수 없습니다.", ErrorType.NOT_FOUND),
    EMAIL_ALREADY_REGISTERED("ADM_MGT_002", "이미 등록된 이메일입니다.", ErrorType.CONFLICT),
    CANNOT_REVOKE_LAST_SUPER_ADMIN("ADM_MGT_003", "마지막 슈퍼어드민은 회수할 수 없습니다.", ErrorType.CONFLICT),
    CANNOT_REVOKE_SELF("ADM_MGT_004", "본인 권한은 회수할 수 없습니다.", ErrorType.CONFLICT),
    MEMBER_PROFILE_REQUIRED("ADM_MGT_005", "멤버 프로필이 먼저 연결되어야 합니다.", ErrorType.CONFLICT),
    INVALID_CURRENT_PASSWORD("ADM_MGT_006", "현재 비밀번호가 일치하지 않습니다.", ErrorType.UNAUTHORIZED),
    INVALID_NEW_PASSWORD("ADM_MGT_007", "비밀번호 정책을 충족하지 않습니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdministratorManagementException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
