package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.crew.CrewDto;
import com.pfplaybackend.api.party.application.dto.dj.DjWithProfileDto;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomWithCrewDto;
import com.pfplaybackend.api.party.application.dto.result.CrewProfileSummaryResult;
import com.pfplaybackend.api.party.application.dto.result.DjQueueInfoResult;
import com.pfplaybackend.api.party.application.dto.result.PartyroomSummaryResult;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.QueueStatus;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartyroomQueryService {

    private final PartyroomAggregatePort aggregatePort;
    private final PartyroomQueryPort queryPort;
    private final UserProfileQueryPort userProfileQueryPort;
    private final PlaybackQueryService playbackQueryService;

    @Transactional(readOnly = true)
    public List<PartyroomWithCrewDto> getAllPartyrooms() {
        return queryPort.getCrewDataByPartyroomId().stream().map(partyroomWithCrewDto -> {
            List<CrewDto> filteredCrews = partyroomWithCrewDto.crews().stream()
                                .filter(crewDto -> crewDto.gradeType().isEqualOrHigherThan(GradeType.MODERATOR))
                                .limit(3)
                                .toList();
            return PartyroomWithCrewDto.from(partyroomWithCrewDto, filteredCrews);
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<UserId, ProfileSettingDto> getPrimariesAvatarSettings(List<PartyroomWithCrewDto> partyrooms) {
        List<UserId> primaryUserIds = partyrooms.stream().collect(Collectors.toMap(
                        PartyroomWithCrewDto::partyroomId,
                        partyroomWithCrewDto -> partyroomWithCrewDto.crews().stream().toList()
                )).values().stream()
                .flatMap(List::stream)
                .map(CrewDto::userId).toList();
        return userProfileQueryPort.getUsersProfileSetting(primaryUserIds);
    }

    @Transactional(readOnly = true)
    public List<CrewData> getActiveCrews(PartyroomId partyroomId) {
        return aggregatePort.findActiveCrews(partyroomId);
    }

    @Transactional(readOnly = true)
    public PartyroomData getPartyroomById(PartyroomId partyroomId) {
        return aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyRegistered(PartyroomId partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        Optional<CrewData> crew = aggregatePort.findCrew(partyroomId, authContext.getUserId());
        if (crew.isEmpty()) return false;
        return aggregatePort.isDjRegistered(partyroomId, new CrewId(crew.get().getId()));
    }

    @Transactional(readOnly = true)
    public List<DjWithProfileDto> getDjs(PartyroomId partyroomId) {
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        // Resolve userIds via crew lookup
        List<Long> crewIds = queuedDjs.stream().map(dj -> dj.getCrewId().getId()).toList();
        List<CrewData> crews = aggregatePort.findCrewsByIds(crewIds);
        Map<Long, UserId> crewIdToUserId = crews.stream()
                .collect(Collectors.toMap(CrewData::getId, CrewData::getUserId));
        List<UserId> userIds = queuedDjs.stream()
                .map(dj -> crewIdToUserId.get(dj.getCrewId().getId()))
                .toList();
        Map<UserId, ProfileSettingDto> profileSettingMap = userProfileQueryPort.getUsersProfileSetting(userIds);

        return queuedDjs.stream().map(dj -> {
            UserId userId = crewIdToUserId.get(dj.getCrewId().getId());
            ProfileSettingDto profileSettingDto = profileSettingMap.get(userId);
            return new DjWithProfileDto(
                    dj.getCrewId().getId(),
                    dj.getOrderNumber(),
                    profileSettingDto.nickname(),
                    profileSettingDto.avatarIconUri()
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ActivePartyroomDto> getMyActivePartyroom() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        return queryPort.getActivePartyroomByUserId(authContext.getUserId());
    }

    @Transactional(readOnly = true)
    public Optional<ActivePartyroomDto> getMyActivePartyroom(UserId userId) {
        return queryPort.getActivePartyroomByUserId(userId);
    }

    @Transactional(readOnly = true)
    public PartyroomSummaryResult getSummaryInfo(PartyroomId partyroomId) {
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        if(playbackState.isActivated()) {
            PlaybackData playback = playbackQueryService.getPlaybackById(playbackState.getCurrentPlaybackId());
            CrewData djCrew = aggregatePort.findCrew(partyroomId, playback.getUserId())
                    .orElseThrow();
            UserId djUserId = djCrew.getUserId();
            ProfileSettingDto profileSettingDto = userProfileQueryPort.getUsersProfileSetting(Collections.singletonList(djUserId)).get(djUserId);
            return new PartyroomSummaryResult(partyroom.getTitle(), partyroom.getIntroduction(),
                    partyroom.getLinkDomain().getValue(), partyroom.getPlaybackTimeLimit().getMinutes(),
                    new DjWithProfileDto(djCrew.getId(), 0, profileSettingDto.nickname(), profileSettingDto.avatarIconUri()));
        }else {
            return new PartyroomSummaryResult(partyroom.getTitle(), partyroom.getIntroduction(),
                    partyroom.getLinkDomain().getValue(), partyroom.getPlaybackTimeLimit().getMinutes(), null);
        }
    }

    /**
     * 현재 재생 중인 곡 이름을 반환한다. 재생 비활성/곡 없음이면 {@code null}.
     *
     * <p>가상 DJ 관찰 파이프라인(RoomContextReader)이 LLM 프롬프트에 "지금 트는 곡" 컨텍스트를
     * 넣기 위해 호출한다. {@link PartyroomAggregatePort} 접근은 party BC 안에 가두고, 호출자에게는
     * 평이한 {@link String} 만 노출한다(가상 DJ 패키지의 ArchUnit AggregatePort 의존 금지 가드 준수).
     */
    @Transactional(readOnly = true)
    public String getCurrentPlaybackName(PartyroomId partyroomId) {
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        if (!playbackState.isActivated() || playbackState.getCurrentPlaybackId() == null) {
            return null;
        }
        return playbackQueryService.getPlaybackById(playbackState.getCurrentPlaybackId()).getName();
    }

    @Transactional(readOnly = true)
    public Optional<CrewData> getCrewByUserId(PartyroomId partyroomId, UserId userId) {
        return aggregatePort.findCrew(partyroomId, userId);
    }

    @Transactional(readOnly = true)
    public CrewData getCrewOrThrow(PartyroomId partyroomId, UserId userId) {
        return aggregatePort.findCrew(partyroomId, userId)
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ACTIVE_ROOM));
    }

    @Transactional(readOnly = true)
    public ActivePartyroomDto getMyActivePartyroomOrThrow(UserId userId) {
        return getMyActivePartyroom(userId)
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ACTIVE_ROOM));
    }

    @Transactional(readOnly = true)
    public DjQueueInfoResult getDjQueueInfo(PartyroomId partyroomId) {
        PartyroomData partyroom = getPartyroomById(partyroomId);
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroom.getPartyroomId());
        DjQueueData djQueue = aggregatePort.findDjQueueState(partyroom.getPartyroomId());
        boolean isPlaybackActivated = playbackState.isActivated();
        QueueStatus queueStatus = djQueue.isClosed() ? QueueStatus.CLOSE : QueueStatus.OPEN;
        boolean isRegistered = isAlreadyRegistered(partyroom.getPartyroomId());
        PlaybackData playback = null;
        if (isPlaybackActivated && playbackState.getCurrentPlaybackId() != null) {
            playback = playbackQueryService.getPlaybackById(playbackState.getCurrentPlaybackId());
        }
        List<DjWithProfileDto> djWithProfiles = getDjs(partyroom.getPartyroomId());
        return new DjQueueInfoResult(isPlaybackActivated, queueStatus, isRegistered, playback, djWithProfiles);
    }

    @Transactional(readOnly = true)
    public CrewProfileSummaryResult getProfileSummaryByCrewId(Long crewId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        ActivePartyroomDto activePartyroomDto = getMyActivePartyroom(authContext.getUserId())
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ACTIVE_ROOM));

        CrewData crew = aggregatePort.findCrewById(crewId)
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ACTIVE_ROOM));
        UserId targetUserId = crew.getUserId();
        AuthorityTier authorityTier = userProfileQueryPort.getAuthorityTier(targetUserId);

        ProfileSummaryDto profileSummaryDto = userProfileQueryPort.getOtherProfileSummary(targetUserId, authorityTier);
        return CrewProfileSummaryResult.from(crewId, profileSummaryDto);
    }
}
