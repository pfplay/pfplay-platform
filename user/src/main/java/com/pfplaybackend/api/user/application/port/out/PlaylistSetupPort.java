package com.pfplaybackend.api.user.application.port.out;

import com.pfplaybackend.api.common.domain.value.UserId;

public interface PlaylistSetupPort {
    void createDefaultPlaylist(UserId userId);

    /**
     * 신규 가입자 온보딩용 기본 PLAYLIST("내 플레이리스트") 생성 — 사람 가입 경로 전용(#329).
     * 봇/가상 멤버 경로에는 배선하지 않는다.
     */
    void createDefaultDjPlaylist(UserId userId);
}
