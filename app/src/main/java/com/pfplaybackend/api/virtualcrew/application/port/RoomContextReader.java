package com.pfplaybackend.api.virtualcrew.application.port;

import com.pfplaybackend.api.party.domain.value.PartyroomId;

/**
 * 가상 DJ LLM 프롬프트에 넣을 방 "컨셉" 컨텍스트를 조립한다.
 *
 * <p>방 제목/소개 + 현재 재생 곡 제목을 한 번에 읽어 {@link RoomContext} 로 돌려준다.
 * party BC 와의 상호작용은 모두 party application query service 를 경유한다
 * (가상 DJ 패키지의 ArchUnit AggregatePort 의존 금지 가드 준수).
 */
public interface RoomContextReader {

    RoomContext read(PartyroomId partyroomId);

    /**
     * 방 컨셉 컨텍스트.
     *
     * @param title          방 제목
     * @param introduction   방 소개(설정에 따라 null/blank 가능)
     * @param nowPlayingTitle 현재 재생 곡 제목 — 재생 비활성/곡 없음이면 {@code null}
     */
    record RoomContext(String title, String introduction, String nowPlayingTitle) {}
}
