package com.pfplaybackend.api.administration.domain.enums;

public enum PartyroomAdminActionType {
    SUSPEND_PARTYROOM,
    RESTORE_PARTYROOM,
    TERMINATE_PARTYROOM,
    SET_FEATURED,
    SET_HIDDEN,
    SET_NORMAL,
    UPDATE_PARTYROOM_META,
    PENALIZE_CREW,           // [PR 9]
    RELEASE_CREW_PENALTY,    // [PR 9]
    PUBLISH_AVATAR_RESOURCE, // [PR 11] partyroom_id=null
    RETIRE_AVATAR_RESOURCE   // [PR 11] partyroom_id=null
    // CHANGE_MEMBER_TIER, WITHDRAW_MEMBER는 PR 12에서 추가
    // 컬럼은 VARCHAR(32)라 마이그레이션 불필요
}
