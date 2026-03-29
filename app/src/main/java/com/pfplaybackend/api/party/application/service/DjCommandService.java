package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.exception.DjException;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.specification.DjEnqueueSpecification;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.DjId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DjCommandService {

    private final PartyroomAggregatePort aggregatePort;
    private final PlaybackControlPort playbackControlPort;
    private final PlaylistQueryPort playlistQueryPort;
    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregateService partyroomAggregateService;
    private final PartyroomQueryService partyroomQueryService;

    @Transactional
    public Long enqueueDj(PartyroomId partyroomId, PlaylistId playlistId)  {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        DjQueueData djQueue = aggregatePort.findDjQueueState(partyroomId);

        boolean isPostActivationProcessingRequired = !playbackState.isActivated();

        // Find crew
        CrewData crew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        CrewId crewId = new CrewId(crew.getId());

        boolean isAlreadyRegistered = aggregatePort.isDjRegistered(partyroomId, crewId);
        boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());
        new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isEmptyPlaylist);

        // Calculate next order number
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        int nextOrder = queuedDjs.size() + 1;

        // Create and save DJ
        DjData dj = DjData.create(partyroom.getPartyroomId(), playlistId, crewId, nextOrder);
        DjData saved = aggregatePort.saveDj(dj);

        if (isPostActivationProcessingRequired) {
            playbackState.activate(null, null);
            aggregatePort.savePlaybackState(playbackState);
        }

        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.ENQUEUE, crewId));

        if (isPostActivationProcessingRequired) {
            playbackControlPort.startPlayback(partyroom);
        }
        return saved.getId();
    }

    @Transactional
    public void dequeueDj(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);

        CrewData crew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        CrewId crewId = new CrewId(crew.getId());

        boolean wasCurrentDj = playbackState.isActivated() && playbackState.isCurrentDj(crewId);
        partyroomAggregateService.removeDjFromQueue(partyroomId, crewId);

        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE, crewId));
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroomId);
        }
    }

    @Transactional
    public void dequeueDj(PartyroomId partyroomId, DjId djId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);

        // 관리자 등급 체크
        CrewData adjusterCrew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        if (adjusterCrew.isBelowGrade(GradeType.MODERATOR))
            throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);

        // 대상 DJ 조회
        DjData targetDj = aggregatePort.findDjById(djId.getId())
                .orElseThrow(() -> ExceptionCreator.create(DjException.NOT_FOUND_DJ));

        boolean wasCurrentDj = playbackState.isActivated() && playbackState.isCurrentDj(targetDj.getCrewId());
        partyroomAggregateService.removeDjFromQueue(partyroomId, targetDj.getCrewId());

        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_ADMIN, targetDj.getCrewId()));
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroomId);
        }
    }

}
