package com.pfplaybackend.api.party.adapter.out.event;

import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.adapter.in.listener.message.*;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.application.dto.crew.CrewSummaryDto;
import com.pfplaybackend.api.party.application.dto.playback.AggregationDto;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.event.*;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventRedisRelay {

    private final RedisMessagePublisher messagePublisher;
    private final UserProfileQueryPort userProfileService;
    private final PartyroomQueryService partyroomQueryService;
    private final CrewRepository crewRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CrewAccessedEvent event) {
        if (event.getAccessType() == AccessType.ENTER) {
            CrewData crew = crewRepository.findById(event.getCrewId().getId()).orElseThrow();
            ProfileSettingDto profile = userProfileService.getUserProfileSetting(event.getUserId());
            CrewSummaryDto crewSummary = CrewSummaryDto.from(crew, profile);
            messagePublisher.publish(MessageTopic.CREW_ENTERED.topic(),
                    CrewEnteredMessage.create(event.getPartyroomId(), crewSummary));
        } else {
            messagePublisher.publish(MessageTopic.CREW_EXITED.topic(),
                    CrewExitedMessage.create(event.getPartyroomId(), event.getCrewId().getId()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(DjQueueChangedEvent event) {
        DjQueueChangeMessage message = DjQueueChangeMessage.create(
                event.getPartyroomId(),
                partyroomQueryService.getDjs(event.getPartyroomId()));
        log.info("[relay] DJ_QUEUE_CHANGED - partyroomId={}, messageId={}",
                event.getPartyroomId().getId(), message.id());
        messagePublisher.publish(MessageTopic.DJ_QUEUE_CHANGED.topic(), message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PlaybackStartedEvent event) {
        PlaybackStartMessage message = new PlaybackStartMessage(event.getPartyroomId(), MessageTopic.PLAYBACK_STARTED,
                UUID.randomUUID().toString(), System.currentTimeMillis(),
                event.getCrewId().getId(), event.getPlayback());
        log.info("[relay] PLAYBACK_STARTED - partyroomId={}, messageId={}",
                event.getPartyroomId().getId(), message.id());
        messagePublisher.publish(MessageTopic.PLAYBACK_STARTED.topic(), message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PlaybackDeactivatedEvent event) {
        PartyroomDeactivationMessage message = new PartyroomDeactivationMessage(event.getPartyroomId(),
                MessageTopic.PLAYBACK_DEACTIVATED, UUID.randomUUID().toString(), System.currentTimeMillis());
        log.info("[relay] PLAYBACK_DEACTIVATED - partyroomId={}, messageId={}",
                event.getPartyroomId().getId(), message.id());
        messagePublisher.publish(MessageTopic.PLAYBACK_DEACTIVATED.topic(), message);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CrewGradeChangedEvent event) {
        messagePublisher.publish(MessageTopic.CREW_GRADE_CHANGED.topic(),
                CrewGradeMessage.from(event.getPartyroomId(), event.getAdjusterCrewId(),
                        event.getAdjustedCrewId(), event.getPrevGrade(), event.getCurrGrade()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CrewPenalizedEvent event) {
        messagePublisher.publish(MessageTopic.CREW_PENALIZED.topic(),
                CrewPenaltyMessage.from(event.getPartyroomId(), event.getPunisherCrewId(),
                        event.getPunishedCrewId(), event.getDetail(), event.getPenaltyType()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ReactionMotionChangedEvent event) {
        messagePublisher.publish(MessageTopic.REACTION_PERFORMED.topic(),
                ReactionMotionMessage.from(event.getPartyroomId(), event.getReactionType(),
                        event.getMotionType(), event.getCrewId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ReactionAggregationChangedEvent event) {
        messagePublisher.publish(MessageTopic.REACTION_AGGREGATION_UPDATED.topic(),
                new ReactionAggregationMessage(event.getPartyroomId(), MessageTopic.REACTION_AGGREGATION_UPDATED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        new AggregationDto(event.getLikeCount(), event.getDislikeCount(), event.getGrabCount())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomClosedEvent event) {
        messagePublisher.publish(MessageTopic.PARTYROOM_CLOSED.topic(),
                new PartyroomClosedMessage(event.getPartyroomId(), MessageTopic.PARTYROOM_CLOSED,
                        UUID.randomUUID().toString(), System.currentTimeMillis()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomTerminatedEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_TERMINATED.topic(),
                new RoomTerminatedMessage(event.getPartyroomId(), MessageTopic.ROOM_TERMINATED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId(), event.getReason()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomSuspendedEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_SUSPENDED.topic(),
                new RoomSuspendedMessage(event.getPartyroomId(), MessageTopic.ROOM_SUSPENDED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId(), event.getReason()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomRestoredEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_RESTORED.topic(),
                new RoomRestoredMessage(event.getPartyroomId(), MessageTopic.ROOM_RESTORED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId()));
    }
}
