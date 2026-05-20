package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
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
import com.pfplaybackend.api.party.domain.specification.DjChangePlaylistSpecification;
import com.pfplaybackend.api.party.domain.specification.DjEnqueueSpecification;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.DjId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
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
        log.info("[enqueueDj] ENTER - requestId={}, partyroomId={}, userId={}, playlistId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), authContext.getUserId().getUid(), playlistId.getId());
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        DjQueueData djQueue = aggregatePort.findDjQueueState(partyroomId);

        boolean isPostActivationProcessingRequired = !playbackState.isActivated();

        // Find crew
        CrewData crew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        CrewId crewId = new CrewId(crew.getId());
        log.info("[enqueueDj] CREW_FOUND - requestId={}, partyroomId={}, userId={}, crewId={}, isActive={}, grade={}",
                RequestIdInterceptor.current(), partyroomId.getId(), authContext.getUserId().getUid(),
                crew.getId(), crew.isActive(), crew.getGradeType());

        boolean isAlreadyRegistered = aggregatePort.isDjRegistered(partyroomId, crewId);
        if (isAlreadyRegistered) {
            log.warn("[enqueueDj] ALREADY_REGISTERED - requestId={}, partyroomId={}, crewId={}",
                    RequestIdInterceptor.current(), partyroomId.getId(), crew.getId());
        }
        boolean isOwned = playlistQueryPort.isOwnedBy(playlistId.getId(), authContext.getUserId().getUid());
        boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());
        new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isOwned, isEmptyPlaylist);
        log.info("[enqueueDj] VALIDATION_PASSED - requestId={}, partyroomId={}, crewId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), crew.getId());

        // Calculate next order number
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        int nextOrder = queuedDjs.size() + 1;

        // Create and save DJ
        DjData dj = DjData.create(partyroom.getPartyroomId(), playlistId, crewId, nextOrder);
        DjData saved = aggregatePort.saveDj(dj);
        log.info("[enqueueDj] SAVED - requestId={}, partyroomId={}, crewId={}, djId={}, orderNumber={}",
                RequestIdInterceptor.current(), partyroomId.getId(), crew.getId(), saved.getId(), nextOrder);

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
        log.info("[dequeueDj-admin] requestId={}, partyroomId={}, adjusterUserId={}, targetDjId={}, targetCrewId={}, wasCurrentDj={}",
                RequestIdInterceptor.current(), partyroomId.getId(), authContext.getUserId().getUid(),
                djId.getId(), targetDj.getCrewId().getId(), wasCurrentDj);
        partyroomAggregateService.removeDjFromQueue(partyroomId, targetDj.getCrewId());

        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_ADMIN, targetDj.getCrewId()));
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroomId);
        }
    }

    @Transactional
    public void changePlaylist(PartyroomId partyroomId, PlaylistId newPlaylistId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        Long userId = authContext.getUserId().getUid();
        log.info("[changePlaylist] ENTER - requestId={}, partyroomId={}, userId={}, newPlaylistId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), userId, newPlaylistId.getId());

        partyroomQueryService.getPartyroomById(partyroomId);
        PartyroomPlaybackData playback = aggregatePort.findPlaybackState(partyroomId);
        DjQueueData djQueue = aggregatePort.findDjQueueState(partyroomId);

        CrewData crew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        CrewId crewId = new CrewId(crew.getId());

        DjData me = aggregatePort.findDj(partyroomId, crewId)
                .orElseThrow(() -> ExceptionCreator.create(DjException.NOT_FOUND_DJ));

        boolean isCurrentDj     = playback.isActivated() && playback.isCurrentDj(crewId);
        boolean isOwned         = playlistQueryPort.isOwnedBy(newPlaylistId.getId(), userId);
        boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(newPlaylistId.getId());

        new DjChangePlaylistSpecification().validate(djQueue, isCurrentDj, isOwned, isEmptyPlaylist);

        Long oldPlaylistId = me.getPlaylistId() != null ? me.getPlaylistId().getId() : null;
        me.updatePlaylist(newPlaylistId);
        // ※ saveDj 명시 호출 안 함 — me 는 @Transactional 컨텍스트의 managed entity,
        //   JPA dirty check 가 commit 시 UPDATE 자동 발행. spec §3-4

        log.info("[changePlaylist] OK - requestId={}, partyroomId={}, crewId={}, oldPlaylistId={}, newPlaylistId={}",
                RequestIdInterceptor.current(), partyroomId.getId(), crewId.getId(), oldPlaylistId, newPlaylistId.getId());
        // 도메인 이벤트 발행 없음 — WS broadcast 불필요(spec §2-2)
    }

}
