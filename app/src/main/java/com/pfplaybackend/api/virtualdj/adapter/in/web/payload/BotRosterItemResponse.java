package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import com.pfplaybackend.api.virtualdj.application.dto.BotRosterRow;

/** 봇 로스터 1행(신원+현재 아바타+배치룸+매핑 페르소나) 응답. */
public record BotRosterItemResponse(Long userId, String nickname, String avatarBodyUri, String avatarIconUri,
                                    Long placementRoomId, String placementRoomTitle,
                                    Long personaId, String personaName) {
    public static BotRosterItemResponse from(BotRosterRow r) {
        return new BotRosterItemResponse(r.userId(), r.nickname(), r.avatarBodyUri(), r.avatarIconUri(),
                r.placementPartyroomId(), r.placementPartyroomTitle(),
                r.personaId(), r.personaName());
    }
}
