package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackRepository;
import com.pfplaybackend.api.party.application.dto.playback.PlaybackDurationWaitDto;
import com.pfplaybackend.api.party.application.port.out.ExpirationTaskPort;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.DjChangeType;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.exception.GradeException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackCommandService implements PlaybackControlPort {

    private final PlaybackRepository playbackRepository;
    private final PlaybackAggregationRepository playbackAggregationRepository;
    private final PlaylistCommandPort playlistCommandPort;
    private final UserActivityPort userActivityPort;
    private final ApplicationEventPublisher eventPublisher;
    private final PartyroomAggregatePort aggregatePort;
    private final ExpirationTaskPort expirationTaskPort;
    private final PartyroomAggregateService partyroomAggregateService;
    private final PartyroomQueryService partyroomQueryService;
    private final Clock clock;

    private void scheduleTask(PlaybackData playback) {
        long seconds = playback.getDuration().toSeconds();
        PartyroomId partyroomId = playback.getPartyroomId();
        UserId userId = playback.getUserId();
        PlaybackDurationWaitDto playbackDurationWaitMessage = new PlaybackDurationWaitDto(partyroomId, userId);
        expirationTaskPort.scheduleExpiration(String.valueOf(partyroomId.getId()), playbackDurationWaitMessage, seconds, TimeUnit.SECONDS);
    }

    private void cancelTask(PartyroomId partyroomId) {
        expirationTaskPort.cancelExpiration(String.valueOf(partyroomId.getId()));
    }

    @Transactional
    public void complete(PartyroomId partyroomId, UserId userId) {
        tryProceed(partyroomId);
        userActivityPort.updateDjPointScore(userId, 1);
    }

    @Transactional
    public void skipByManager(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        // #297: 등급 검사는 skip이 적용되는 경로의 룸 기준이어야 한다.
        // '내 active 룸' 우회 조회는 active crew 부재 시 500, 타 룸 운영진의 cross-room skip을 허용했다.
        CrewData adjusterCrew = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
        if (adjusterCrew.isBelowGrade(GradeType.MODERATOR)) throw ExceptionCreator.create(GradeException.MANAGER_GRADE_REQUIRED);
        cancelTask(partyroomId);
        tryProceed(partyroomId);
    }

    @Override
    @Transactional
    public void skipPlayback(PartyroomId partyroomId) {
        cancelTask(partyroomId);
        tryProceed(partyroomId);
    }

    private void tryProceed(PartyroomId partyroomId) {
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        log.debug("[tryProceed] partyroomId={}, queueSize={}", partyroomId.getId(), queuedDjs.size());

        if(!queuedDjs.isEmpty()) {
            doStart(partyroom);
        }else{
            log.info("[tryProceed] EMPTY_QUEUE_DEACTIVATE - partyroomId={}", partyroomId.getId());
            deactivateAndNotify(partyroom);
        }
    }

    @Override
    public void startPlayback(PartyroomData partyroom) {
        doStart(partyroom);
    }

    private void doStart(PartyroomData partyroom) {
        long partyroomIdValue = partyroom.getPartyroomId().getId();
        List<DjData> rotatedDjs = partyroomAggregateService.rotateDjQueue(partyroom.getPartyroomId());
        int limitMin = partyroom.getPlaybackTimeLimit().getMinutes();
        log.debug("[doStart] ENTER - partyroomId={}, queueSize={}, limitMin={}",
                partyroomIdValue, rotatedDjs.size(), limitMin);

        List<DjData> orderedDjs = rotatedDjs.stream()
                .sorted(Comparator.comparingInt(DjData::getOrderNumber))
                .toList();

        for (DjData dj : orderedDjs) {
            CrewData djCrew = aggregatePort.findCrewById(dj.getCrewId().getId()).orElseThrow();
            long djCrewId = djCrew.getId();
            List<PlaybackTrackDto> peeked = playlistCommandPort.peekTracksFromCursor(dj.getPlaylistId());

            PlaybackTrackDto chosen = peeked.stream()
                    .filter(t -> !partyroom.getPlaybackTimeLimit().exceedsDuration(t.duration()))
                    .findFirst()
                    .orElse(null);

            if (chosen == null) {
                log.warn("[doStart] DJ_NO_PLAYABLE_TRACK - partyroomId={}, djCrewId={}, peekedCount={}, limitMin={} - skipping to next DJ",
                        partyroomIdValue, djCrewId, peeked.size(), limitMin);
                continue;
            }

            log.debug("[doStart] PLAYABLE_TRACK - partyroomId={}, djCrewId={}, trackName={}, durationSec={}, orderNumber={}, limitMin={}",
                    partyroomIdValue, djCrewId, chosen.name(), chosen.duration().toSeconds(), chosen.orderNumber(), limitMin);

            startPlaybackFor(partyroom, dj, djCrew, chosen);
            return;
        }

        log.warn("[doStart] DEACTIVATE_TRIGGERED - partyroomId={}, reason=ALL_DJS_NO_PLAYABLE_TRACK, queueSize={}, limitMin={}",
                partyroomIdValue, orderedDjs.size(), limitMin);
        deactivateAndNotify(partyroom);
    }

    private void startPlaybackFor(PartyroomData partyroom, DjData dj, CrewData djCrew, PlaybackTrackDto chosen) {
        PlaybackData nextPlayback = PlaybackData.create(partyroom.getPartyroomId(), djCrew.getUserId(),
                chosen.name(), chosen.duration(), chosen.linkId(), chosen.thumbnailImage(), clock.instant());
        playlistCommandPort.advancePlaybackCursor(dj.getPlaylistId(), chosen.trackId());

        PlaybackData playbackData = playbackRepository.save(nextPlayback);
        playbackAggregationRepository.save(PlaybackAggregationData.createFor(new PlaybackId(playbackData.getId())));
        // Update playback state in PARTYROOM_PLAYBACK
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        playbackState.updatePlayback(new PlaybackId(playbackData.getId()), new CrewId(djCrew.getId()));
        aggregatePort.savePlaybackState(playbackState);
        // Schedule Task to wait for playback time
        scheduleTask(nextPlayback);
        // Propagation Websocket Event
        PlaybackSnapshot snapshot = new PlaybackSnapshot(
                playbackData.getId(), playbackData.getLinkId(), playbackData.getName(),
                playbackData.getDuration().toDisplayString(), playbackData.getThumbnailImage(), playbackData.getEndTime());
        eventPublisher.publishEvent(new PlaybackStartedEvent(partyroom.getPartyroomId(), new CrewId(djCrew.getId()), snapshot));
        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroom.getPartyroomId(), DjChangeType.ROTATE, new CrewId(djCrew.getId())));
    }

    @Transactional
    public PlaybackAggregationData updatePlaybackAggregation(PlaybackId playbackId, List<Integer> deltaRecord) {
        int updated = playbackAggregationRepository.applyAggregationDelta(
                playbackId,
                deltaRecord.get(0),
                deltaRecord.get(1),
                deltaRecord.get(2)
        );
        if (updated == 0) {
            log.warn("[updatePlaybackAggregation] row missing for playbackId={}", playbackId);
        }
        // applyAggregationDelta는 @Modifying(clearAutomatically=true)이므로 1차 캐시 비워짐.
        // findById는 fresh SELECT로 atomic UPDATE 후 최신 카운터 값 반환 → 호출자가 이벤트 publish에 사용.
        PlaybackAggregationData reloaded = playbackAggregationRepository.findById(playbackId).orElseThrow();

        // Negative-count drift signal: like/dislike are toggle-based deltas computed by
        // PlaybackReactionDomainService against history. Negative counters indicate
        // history vs counter drift — log WARN so it's actionable.
        if (reloaded.getLikeCount() < 0 || reloaded.getDislikeCount() < 0 || reloaded.getGrabCount() < 0) {
            log.warn("[updatePlaybackAggregation] negative counter detected — possible history/counter drift: " +
                     "playbackId={}, likeCount={}, dislikeCount={}, grabCount={}",
                     playbackId, reloaded.getLikeCount(), reloaded.getDislikeCount(), reloaded.getGrabCount());
        }
        return reloaded;
    }

    private void deactivateAndNotify(PartyroomData partyroom) {
        PartyroomId partyroomId = partyroom.getPartyroomId();
        log.warn("[deactivateAndNotify] partyroomId={} - publishing DEACTIVATE event + clearing DJ queue", partyroomId.getId());
        eventPublisher.publishEvent(new DjQueueChangedEvent(partyroomId, DjChangeType.DEACTIVATE, null,
                partyroom.getPlaybackTimeLimit().getMinutes()));
        partyroomAggregateService.deactivatePlayback(partyroomId)
                .forEach(eventPublisher::publishEvent);
    }
}
