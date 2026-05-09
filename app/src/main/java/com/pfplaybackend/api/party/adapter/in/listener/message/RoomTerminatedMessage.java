package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;

public record RoomTerminatedMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        Long administratorId,
        String reason
) implements Serializable, GroupBroadcastMessage {}
