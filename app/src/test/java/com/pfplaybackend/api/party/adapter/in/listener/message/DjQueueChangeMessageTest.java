package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.application.dto.dj.DjWithProfileDto;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DjQueueChangeMessageTest {

    private final PartyroomId partyroomId = new PartyroomId(1L);
    private final List<DjWithProfileDto> djs = List.of(new DjWithProfileDto(10L, 1, "DJ1", "icon1"));

    @Test
    @DisplayName("create — DEACTIVATE + limit 분이 changeType/playbackTimeLimitMinutes 로 보존된다")
    void createWithDeactivateAndLimitCarriesChangeTypeAndLimit() {
        // when
        DjQueueChangeMessage message =
                DjQueueChangeMessage.create(partyroomId, djs, DjChangeType.DEACTIVATE, 5);

        // then
        assertThat(message.changeType()).isEqualTo(DjChangeType.DEACTIVATE);
        assertThat(message.playbackTimeLimitMinutes()).isEqualTo(5);
        assertThat(message.eventType()).isEqualTo(MessageTopic.DJ_QUEUE_CHANGED);
        assertThat(message.djs()).isEqualTo(djs);
        assertThat(message.id()).isNotNull();
    }

    @Test
    @DisplayName("create — DEACTIVATE 외 changeType 은 playbackTimeLimitMinutes 가 null 로 유지된다")
    void createWithNonDeactivateKeepsLimitNull() {
        // when
        DjQueueChangeMessage message =
                DjQueueChangeMessage.create(partyroomId, djs, DjChangeType.ROTATE, null);

        // then
        assertThat(message.changeType()).isEqualTo(DjChangeType.ROTATE);
        assertThat(message.playbackTimeLimitMinutes()).isNull();
    }
}
