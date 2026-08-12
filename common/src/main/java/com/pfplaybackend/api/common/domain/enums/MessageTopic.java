package com.pfplaybackend.api.common.domain.enums;

public enum MessageTopic {
    PLAYBACK_DEACTIVATED,
    CREW_ENTERED,
    CREW_EXITED,
    REACTION_AGGREGATION_UPDATED,
    REACTION_PERFORMED,
    CREW_GRADE_CHANGED,
    CREW_PENALIZED,
    CREW_PROFILE_CHANGED,
    CREW_PROFILE_PRE_CHECK,
    PLAYBACK_STARTED,
    DJ_QUEUE_CHANGED,
    CHAT_MESSAGE_SENT,
    PARTYROOM_NOTICE_UPDATED,
    PARTYROOM_CLOSED,
    ROOM_TERMINATED,    // PR 8 — admin terminate
    ROOM_SUSPENDED,     // PR 8 — admin suspend
    ROOM_RESTORED;      // PR 8 — admin restore

    public String topic() {
        return name().toLowerCase();
    }
}
