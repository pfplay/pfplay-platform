package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.dto.BotRosterRow;

/**
 * 봇 로스터 1행(신원+현재 아바타+배치룸+매핑 페르소나) 응답.
 *
 * <p>{@code userId}(= user_account.user_id)는 TSID라 값이 2^53를 초과한다. JSON 숫자로 내보내면
 * 어드민 프론트의 {@code JSON.parse}가 정밀도를 잃어(끝자리 반올림) 이후 mutation 요청에서
 * 다른 봇을 가리키거나 404(ADM-004)를 낸다. web-facing {@code QueryMyInfoResponse.uid} 와 동일하게
 * 문자열로 직렬화한다. 반면 {@code placementRoomId}·{@code personaId}는 IDENTITY(작은 값)라 Long 유지.
 */
public record BotRosterItemResponse(String userId, String nickname, String avatarBodyUri, String avatarIconUri,
                                    Long placementRoomId, String placementRoomTitle,
                                    Long personaId, String personaName) {
    public static BotRosterItemResponse from(BotRosterRow r) {
        return new BotRosterItemResponse(String.valueOf(r.userId()), r.nickname(), r.avatarBodyUri(), r.avatarIconUri(),
                r.placementPartyroomId(), r.placementPartyroomTitle(),
                r.personaId(), r.personaName());
    }
}
