package com.pfplaybackend.api.virtualdj.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum VirtualDjException implements DomainException {

    SONG_PACK_NOT_FOUND("VDJ-001", "송 팩을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    SONG_PACK_DUPLICATE_NAME("VDJ-002", "이미 사용 중인 송 팩 이름입니다", ErrorType.CONFLICT),
    SONG_PACK_IN_USE("VDJ-003", "송 팩이 사용 중이어서 삭제할 수 없습니다", ErrorType.CONFLICT),
    TRACK_EXCEEDS_PLAYBACK_LIMIT("VDJ-004", "트랙의 재생 시간이 playbackTimeLimit을 초과합니다", ErrorType.BAD_REQUEST),
    PACK_TRACK_NOT_FOUND("VDJ-005", "송 팩 트랙을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    CONFIG_NOT_FOUND("VDJ-006", "해당 룸의 가상 DJ 설정을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    INVALID_CONFIG("VDJ-007", "MANAGED 전환에는 targetCount/companionFloor 가 필요합니다", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    VirtualDjException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
