package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyroomNoticeQueryServiceTest {

    @Mock PartyroomAggregatePort aggregatePort;
    @InjectMocks PartyroomNoticeQueryService partyroomNoticeQueryService;

    @Test
    @DisplayName("getNotice — 파티룸의 공지사항을 반환한다")
    void getNoticeSuccess() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PartyroomData partyroom = PartyroomData.builder()
                .id(1L).hostId(new UserId(1L)).stageType(StageType.GENERAL)
                .title("Test Room").introduction("Welcome")
                .linkDomain(LinkDomain.of("testlink")).playbackTimeLimit(PlaybackTimeLimit.ofMinutes(10))
                .noticeContent("Important Notice").status(PartyroomStatus.ACTIVE).build();

        when(aggregatePort.findPartyroomById(1L)).thenReturn(Optional.of(partyroom));

        // when
        String notice = partyroomNoticeQueryService.getNotice(partyroomId);

        // then
        assertThat(notice).isEqualTo("Important Notice");
    }

    @Test
    @DisplayName("getNotice — 존재하지 않는 파티룸이면 NotFoundException이 발생한다")
    void getNoticeNotFound() {
        // given
        PartyroomId partyroomId = new PartyroomId(999L);
        when(aggregatePort.findPartyroomById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> partyroomNoticeQueryService.getNotice(partyroomId))
                .isInstanceOf(NotFoundException.class);
    }
}
