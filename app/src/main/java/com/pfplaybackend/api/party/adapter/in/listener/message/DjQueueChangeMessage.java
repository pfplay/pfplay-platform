package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.application.dto.dj.DjWithProfileDto;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record DjQueueChangeMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        List<DjWithProfileDto> djs,
        DjChangeType changeType,
        Integer playbackTimeLimitMinutes
) implements Serializable, GroupBroadcastMessage {

    public static DjQueueChangeMessage create(PartyroomId partyroomId, List<DjWithProfileDto> djs,
                                              DjChangeType changeType, Integer playbackTimeLimitMinutes) {
        return new DjQueueChangeMessage(partyroomId, MessageTopic.DJ_QUEUE_CHANGED,
                UUID.randomUUID().toString(), System.currentTimeMillis(), djs,
                changeType, playbackTimeLimitMinutes);
    }
}
