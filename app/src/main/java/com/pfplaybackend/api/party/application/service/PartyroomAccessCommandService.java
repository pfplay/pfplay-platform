package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.specification.PartyroomEntrySpecification;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomAccessCommandService {

    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomAggregateService partyroomAggregateService;
    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackControlPort playbackControlPort;
    private final Clock clock;

    @Transactional
    public CrewData tryEnter(PartyroomId partyroomId, CountryCode countryCode) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        UserId userId = authContext.getUserId();
        log.info("[tryEnter] START - userId={}, targetPartyroomId={}",
                userId, partyroomId.getId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        long activeCrewCount = aggregatePort.countActiveCrews(partyroomId);
        Optional<CrewData> existingCrew = aggregatePort.findCrew(partyroomId, userId);
        log.debug("[tryEnter] Partyroom found - partyroomId={}, isTerminated={}, crewCount={}",
                partyroomId.getId(), partyroom.isTerminated(), activeCrewCount);

        new PartyroomEntrySpecification().validate(partyroom, activeCrewCount, existingCrew);

        // Validate Crew Condition
        Optional<ActivePartyroomDto> optActiveRoomInfo = partyroomQueryService.getMyActivePartyroom(userId);
        log.info("[tryEnter] Active room check - userId={}, hasActiveRoom={}, activeRoomId={}",
                userId,
                optActiveRoomInfo.isPresent(),
                optActiveRoomInfo.map(ActivePartyroomDto::id).orElse(null));

        if (optActiveRoomInfo.isPresent()) {
            ActivePartyroomDto activeRoomInfo = optActiveRoomInfo.get();
            if(!partyroomId.equals(new PartyroomId(activeRoomInfo.id()))) {
                log.info("[tryEnter] Auto-exit from another room - userId={}, exitingRoomId={}, enteringRoomId={}",
                        userId, activeRoomInfo.id(), partyroomId.getId());
                exit(new PartyroomId(activeRoomInfo.id()));
            } else {
                log.info("[tryEnter] Same room re-entry - userId={}, partyroomId={}", userId, partyroomId.getId());
                CrewData crew = aggregatePort.findCrew(partyroomId, userId)
                        .orElseThrow();
                crew.updateCountryCode(countryCode);
                aggregatePort.saveCrew(crew);
                publishAccessChangedEvent(partyroom.getPartyroomId(), crew, userId);
                return crew;
            }
        }

        CrewData crew = addOrActivateCrew(partyroom, userId, countryCode);
        log.info("[tryEnter] SUCCESS - userId={}, partyroomId={}, crewId={}", userId, partyroomId.getId(), crew.getId());
        publishAccessChangedEvent(partyroom.getPartyroomId(), crew, userId);
        return crew;
    }

    private CrewData addOrActivateCrew(PartyroomData partyroom, UserId userId, CountryCode countryCode) {
        Optional<CrewData> existingCrew = aggregatePort.findCrew(partyroom.getPartyroomId(), userId);
        if (existingCrew.isPresent() && !existingCrew.get().isActive()) {
            CrewData crew = existingCrew.get();
            log.info("[addOrActivateCrew] Reactivating inactive crew - userId={}, partyroomId={}",
                    userId, partyroom.getPartyroomId().getId());
            crew.activatePresence(LocalDateTime.now(clock));
            crew.updateCountryCode(countryCode);
            return aggregatePort.saveCrew(crew);
        } else {
            log.info("[addOrActivateCrew] Adding new crew - userId={}, partyroomId={}, gradeType=LISTENER",
                    userId, partyroom.getPartyroomId().getId());
            CrewData crew = CrewData.create(partyroom.getPartyroomId(), userId, GradeType.LISTENER, countryCode, LocalDateTime.now(clock));
            return aggregatePort.saveCrew(crew);
        }
    }

    private void publishAccessChangedEvent(PartyroomId partyroomId, CrewData crew, UserId userId) {
        eventPublisher.publishEvent(new CrewAccessedEvent(partyroomId, new CrewId(crew.getId()), userId, AccessType.ENTER));
    }

    @Transactional
    public void enterByHost(UserId hostId, PartyroomData partyroom) {
        CrewData crew = CrewData.create(partyroom.getPartyroomId(), hostId, GradeType.HOST, null, LocalDateTime.now(clock));
        aggregatePort.saveCrew(crew);
    }

    @Transactional
    public void exit(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        log.info("[exit] START - userId={}, partyroomId={}", authContext.getUserId(), partyroomId.getId());

        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);

        Optional<CrewData> optionalCrew = aggregatePort.findCrew(partyroomId, authContext.getUserId());
        if(optionalCrew.isEmpty()) {
            log.warn("[exit] INVALID_ACTIVE_ROOM - userId={} has no active crew in partyroomId={}",
                    authContext.getUserId(), partyroomId.getId());
            throw ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM);
        }

        CrewData crew = optionalCrew.get();
        crew.deactivatePresence(LocalDateTime.now(clock));
        aggregatePort.saveCrew(crew);

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));

        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()), authContext.getUserId(), AccessType.EXIT));
    }

    @Transactional
    public void expel(PartyroomData partyroom, CrewData crew, boolean isPermanent)  {
        crew.deactivatePresence(LocalDateTime.now(clock));
        if(isPermanent) crew.enforceBan();
        aggregatePort.saveCrew(crew);

        handleDjQueueOnLeave(partyroom, new CrewId(crew.getId()));

        eventPublisher.publishEvent(new CrewAccessedEvent(partyroom.getPartyroomId(), new CrewId(crew.getId()), crew.getUserId(), AccessType.EXIT));
    }

    private void handleDjQueueOnLeave(PartyroomData partyroom, CrewId crewId) {
        boolean wasInDjQueue = aggregatePort.findDj(partyroom.getPartyroomId(), crewId)
                .isPresent();
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        boolean wasCurrentDj = playbackState.isActivated() && wasInDjQueue
                && playbackState.isCurrentDj(crewId);

        partyroomAggregateService.removeDjFromQueue(partyroom.getPartyroomId(), crewId);

        if (wasInDjQueue) {
            eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.DEQUEUE_EXIT, crewId));
        }
        if (wasCurrentDj) {
            playbackControlPort.skipPlayback(partyroom.getPartyroomId());
        }
    }
}
