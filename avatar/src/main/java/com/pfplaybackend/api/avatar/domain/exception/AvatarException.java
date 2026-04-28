package com.pfplaybackend.api.avatar.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * 아바타 카탈로그 도메인 예외. Spec §6.I-8.
 *
 * <p>호출자는 {@code throw ExceptionCreator.create(AvatarException.XXX)} 패턴 사용 (다른 BC와 동일).
 */
@Getter
public enum AvatarException implements DomainException {
    AVATAR_NAME_ALREADY_EXISTS(
            "AVT-001", "이미 동일한 이름의 아바타 리소스가 있습니다.", ErrorType.CONFLICT),
    AVATAR_INVALID_FILE_FORMAT(
            "AVT-002", "지원하지 않는 파일 포맷입니다. PNG/JPEG만 허용됩니다.", ErrorType.BAD_REQUEST),
    AVATAR_FILE_TOO_LARGE(
            "AVT-003", "허용 파일 크기를 초과했습니다.", ErrorType.BAD_REQUEST),
    AVATAR_STORAGE_UPLOAD_FAILED(
            "AVT-004", "스토리지 업로드에 실패했습니다.", ErrorType.BAD_REQUEST),
    AVATAR_INVALID_LIFECYCLE_TRANSITION(
            "AVT-005", "허용되지 않은 라이프사이클 전이입니다.", ErrorType.CONFLICT),
    AVATAR_RESOURCE_RETIRED(
            "AVT-006", "이미 retire된 리소스는 수정할 수 없습니다.", ErrorType.CONFLICT),
    AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH(
            "AVT-007", "PUBLISHED 이후에는 이미지를 교체할 수 없습니다.", ErrorType.CONFLICT),
    AVATAR_INVALID_DEFAULT_SETTING(
            "AVT-008", "기본 설정 가능 조건을 충족하지 않습니다 (BASIC + PUBLISHED, score=0).", ErrorType.BAD_REQUEST),
    AVATAR_RESOURCE_NOT_FOUND(
            "AVT-009", "아바타 리소스를 찾을 수 없습니다.", ErrorType.NOT_FOUND);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AvatarException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
