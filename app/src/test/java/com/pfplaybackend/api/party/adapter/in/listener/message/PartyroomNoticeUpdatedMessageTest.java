package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomNoticeUpdatedMessageTest {

    @Test
    @DisplayName("create — 파티룸 공지 이벤트 메타데이터와 내용을 생성한다")
    void createBuildsNoticeMessage() {
        PartyroomId partyroomId = new PartyroomId(1L);

        PartyroomNoticeUpdatedMessage message =
                PartyroomNoticeUpdatedMessage.create(partyroomId, "new notice");

        assertThat(message.partyroomId()).isEqualTo(partyroomId);
        assertThat(message.eventType()).isEqualTo(MessageTopic.PARTYROOM_NOTICE_UPDATED);
        assertThat(message.id()).isNotBlank();
        assertThat(message.timestamp()).isPositive();
        assertThat(message.content()).isEqualTo("new notice");
    }
}
