package com.pfplaybackend.api.party.domain.enums;

/**
 * DJ 큐 엔트리 종류.
 * NORMAL — 플레이리스트 기반 상시 회전 엔트리(기존 동작).
 * ONE_SHOT — Quick-DJ 로 등록된 1회 재생 엔트리. 재생 완료/스킵 시 큐에서 자동 이탈한다(spec §3-3).
 */
public enum DjKind {
    NORMAL,
    ONE_SHOT
}
