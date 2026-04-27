package com.pfplaybackend.api.administration.domain.enums;

public enum PartyroomAdminActionType {
    SUSPEND_PARTYROOM,
    RESTORE_PARTYROOM,
    TERMINATE_PARTYROOM,
    SET_FEATURED,
    SET_HIDDEN,
    SET_NORMAL,
    UPDATE_PARTYROOM_META
    // PENALIZE_CREW deferred to PR 9, MEMBER actions to PR 12
}
