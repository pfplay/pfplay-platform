package com.pfplaybackend.api.party.adapter.out.event;

import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.application.dto.dj.DjWithProfileDto;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.event.*;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DomainEventRedisRelayTest {

    @Mock RedisMessagePublisher messagePublisher;
    @Mock UserProfileQueryPort userProfileService;
    @Mock PartyroomQueryService partyroomQueryService;
    @Mock CrewRepository crewRepository;

    @InjectMocks DomainEventRedisRelay domainEventRedisRelay;

    private final PartyroomId partyroomId = new PartyroomId(1L);
    private final UserId userId = new UserId(100L);
    private final CrewId crewId = new CrewId(10L);

    @Test
    @DisplayName("on(CrewAccessedEvent) — ENTER 시 프로필 포함 메시지가 발행된다")
    void onCrewAccessedEventEnterPublishesWithProfile() {
        // given
        CrewAccessedEvent event = new CrewAccessedEvent(partyroomId, crewId, userId, AccessType.ENTER);
        CrewData crew = CrewData.builder()
                .id(crewId.getId()).partyroomId(partyroomId).userId(userId).gradeType(GradeType.CLUBBER).build();
        ProfileSettingDto profile = new ProfileSettingDto(
                "nickname", AvatarCompositionType.SINGLE_BODY,
                "bodyUri", "faceUri", "iconUri", 0, 0, 0.0, 0.0, 1.0
        );

        when(crewRepository.findById(crewId.getId())).thenReturn(Optional.of(crew));
        when(userProfileService.getUserProfileSetting(userId)).thenReturn(profile);

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(crewRepository).findById(crewId.getId());
        verify(userProfileService).getUserProfileSetting(userId);
        verify(messagePublisher).publish(eq(MessageTopic.CREW_ENTERED.topic()), any());
    }

    @Test
    @DisplayName("on(CrewAccessedEvent) — EXIT 시 crewId만 포함된 메시지가 발행된다")
    void onCrewAccessedEventExitPublishesWithCrewIdOnly() {
        // given
        CrewAccessedEvent event = new CrewAccessedEvent(partyroomId, crewId, userId, AccessType.EXIT);

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(crewRepository, never()).findById(any());
        verify(messagePublisher).publish(eq(MessageTopic.CREW_EXITED.topic()), any());
    }

    @Test
    @DisplayName("on(DjQueueChangedEvent) — DJ 큐 변경 메시지가 발행된다")
    void onDjQueueChangedEventPublishesMessage() {
        // given
        DjQueueChangedEvent event = new DjQueueChangedEvent(partyroomId, DjChangeType.ENQUEUE, crewId);
        List<DjWithProfileDto> djs = List.of(new DjWithProfileDto(crewId.getId(), 1, "DJ1", "icon1"));
        when(partyroomQueryService.getDjs(partyroomId)).thenReturn(djs);

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.DJ_QUEUE_CHANGED.topic()), any());
    }

    @Test
    @DisplayName("on(PlaybackStartedEvent) — 재생 시작 메시지가 발행된다")
    void onPlaybackStartedEventPublishesMessage() {
        // given
        PlaybackSnapshot snapshot = new PlaybackSnapshot(1L, "linkId", "Song", "3:30", "thumb.jpg", 9999L);
        PlaybackStartedEvent event = new PlaybackStartedEvent(partyroomId, crewId, snapshot);

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.PLAYBACK_STARTED.topic()), any());
    }

    @Test
    @DisplayName("on(PlaybackDeactivatedEvent) — 비활성화 메시지가 발행된다")
    void onPlaybackDeactivatedEventPublishesMessage() {
        // given
        PlaybackDeactivatedEvent event = new PlaybackDeactivatedEvent(partyroomId, new PlaybackId(1L), crewId);

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.PLAYBACK_DEACTIVATED.topic()), any());
    }

    @Test
    @DisplayName("on(CrewGradeChangedEvent) — 등급 변경 메시지가 발행된다")
    void onCrewGradeChangedEventPublishesMessage() {
        // given
        CrewId adjusterCrewId = new CrewId(10L);
        CrewId adjustedCrewId = new CrewId(20L);
        CrewGradeChangedEvent event = new CrewGradeChangedEvent(
                partyroomId, adjusterCrewId, adjustedCrewId, GradeType.CLUBBER, GradeType.MODERATOR
        );

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.CREW_GRADE_CHANGED.topic()), any());
    }

    @Test
    @DisplayName("on(CrewPenalizedEvent) — 제재 메시지가 발행된다")
    void onCrewPenalizedEventPublishesMessage() {
        // given
        CrewId punisherCrewId = new CrewId(10L);
        CrewId punishedCrewId = new CrewId(20L);
        CrewPenalizedEvent event = new CrewPenalizedEvent(
                partyroomId, punisherCrewId, punishedCrewId, 999L, "Bad behavior", PenaltyType.ONE_TIME_EXPULSION
        );

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.CREW_PENALIZED.topic()), any());
    }

    @Test
    @DisplayName("on(PartyroomClosedEvent) — 파티룸 종료 메시지가 발행된다")
    void onPartyroomClosedEventPublishesMessage() {
        // given
        PartyroomClosedEvent event = new PartyroomClosedEvent(partyroomId, userId, "Test Room");

        // when
        domainEventRedisRelay.on(event);

        // then
        verify(messagePublisher).publish(eq(MessageTopic.PARTYROOM_CLOSED.topic()), any());
    }
}
