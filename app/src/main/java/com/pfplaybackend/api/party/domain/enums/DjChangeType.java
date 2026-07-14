package com.pfplaybackend.api.party.domain.enums;

public enum DjChangeType {
    ENQUEUE,
    DEQUEUE,
    DEQUEUE_ADMIN,
    DEQUEUE_EXIT,
    ROTATE,
    DEACTIVATE,
    /** Quick-DJ(#331) — ONE_SHOT 엔트리가 1회 재생을 마치고 자연 이탈 */
    ONE_SHOT_COMPLETED
}
