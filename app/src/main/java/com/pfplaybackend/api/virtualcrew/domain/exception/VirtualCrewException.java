package com.pfplaybackend.api.virtualcrew.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum VirtualCrewException implements DomainException {

    SONG_PACK_NOT_FOUND("VCREW-001", "송 팩을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    SONG_PACK_DUPLICATE_NAME("VCREW-002", "이미 사용 중인 송 팩 이름입니다", ErrorType.CONFLICT),
    SONG_PACK_IN_USE("VCREW-003", "송 팩이 사용 중이어서 삭제할 수 없습니다", ErrorType.CONFLICT),
    TRACK_EXCEEDS_PLAYBACK_LIMIT("VCREW-004", "트랙의 재생 시간이 playbackTimeLimit을 초과합니다", ErrorType.BAD_REQUEST),
    PACK_TRACK_NOT_FOUND("VCREW-005", "송 팩 트랙을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    CONFIG_NOT_FOUND("VCREW-006", "해당 룸의 가상 DJ 설정을 찾을 수 없습니다", ErrorType.NOT_FOUND),
    INVALID_CONFIG("VCREW-007", "MANAGED 전환에는 targetCount/djBotCount 가 필요합니다", ErrorType.BAD_REQUEST),
    INVALID_AVATAR_SET("VCREW-008", "유효하지 않은 아바타 셋입니다 (빈 셋·빈 봇목록·미존재 바디 URI)", ErrorType.BAD_REQUEST),
    PERSONA_NOT_FOUND("VCREW-009", "페르소나를 찾을 수 없습니다.", ErrorType.NOT_FOUND),
    PERSONA_DUPLICATE_NAME("VCREW-010", "이미 존재하는 페르소나 이름입니다.", ErrorType.CONFLICT),
    PERSONA_INACTIVE("VCREW-011", "비활성 페르소나는 새로 매핑할 수 없습니다.", ErrorType.BAD_REQUEST),
    PERSONA_IN_USE("VCREW-012", "봇에 매핑된 페르소나는 삭제할 수 없습니다. 먼저 매핑을 해제하세요.", ErrorType.CONFLICT),
    CHAT_CONFIG_INVALID("VCREW-013", "채팅 설정 값이 유효하지 않습니다.", ErrorType.BAD_REQUEST),
    DJ_COUNT_EXCEEDS_TRACKS("VCREW-014", "djBotCount 가 필터 통과 트랙 수를 초과합니다", ErrorType.BAD_REQUEST),
    BOT_PLACED_CANNOT_REMOVE("VCREW-015", "배치된 봇은 제거할 수 없습니다. 먼저 해당 방을 리소스 회수/재배치하세요", ErrorType.CONFLICT);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    VirtualCrewException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
