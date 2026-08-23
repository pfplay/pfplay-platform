package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;
import java.util.UUID;

public record PartyroomNoticeUpdatedMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        String content
) implements Serializable, GroupBroadcastMessage {

    public static PartyroomNoticeUpdatedMessage create(PartyroomId partyroomId, String content) {
        return new PartyroomNoticeUpdatedMessage(
                partyroomId,
                MessageTopic.PARTYROOM_NOTICE_UPDATED,
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                content);
    }
}
