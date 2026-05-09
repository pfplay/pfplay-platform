package com.pfplaybackend.api.administration.domain.enums;

/**
 * user_activity_log.event_type 컬럼(V10) 매핑.
 *
 * 10종 catalog 전체 미리 정의 — PR 12b가 wiring만 추가하면 됨, enum 재배포 회피.
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.7.2
 *
 * PR 12a 시점 listener 핸들러 7종:
 *   SIGNED_UP, SIGNED_IN, PROFILE_UPDATED, PARTYROOM_CREATED,
 *   PARTYROOM_ENTERED, PARTYROOM_EXITED, PENALIZED_IN_PARTYROOM
 *
 * PR 12b 시점 listener 핸들러 3종 (현 시점 미사용 enum 값):
 *   WITHDREW, TIER_CHANGED, ADMIN_ACTED_ON
 */
public enum UserActivityEventType {
    SIGNED_UP, SIGNED_IN, WITHDREW, PROFILE_UPDATED, TIER_CHANGED,
    PARTYROOM_CREATED, PARTYROOM_ENTERED, PARTYROOM_EXITED,
    PENALIZED_IN_PARTYROOM, ADMIN_ACTED_ON
}
