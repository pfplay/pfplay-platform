package com.pfplaybackend.api.playlist.domain.enums;

public enum PlaylistType {
    GRABLIST,
    PLAYLIST,
    /** Quick-DJ(#331) one-shot 곡 저장용 per-user 숨김 플리 — 목록/단건 조회에서 제외된다. @Enumerated(STRING)이라 append 안전. */
    TEMP
}
