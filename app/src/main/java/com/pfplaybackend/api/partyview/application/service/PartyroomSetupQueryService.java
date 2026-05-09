package com.pfplaybackend.api.partyview.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.playback.AggregationDto;
import com.pfplaybackend.api.party.application.dto.playback.PlaybackDto;
import com.pfplaybackend.api.party.application.dto.playback.ReactionHistoryDto;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackQueryService;
import com.pfplaybackend.api.party.application.service.PlaybackReactionQueryService;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.partyview.application.dto.CrewSetupDto;
import com.pfplaybackend.api.partyview.application.dto.CurrentDjDto;
import com.pfplaybackend.api.partyview.application.dto.DisplayDto;
import com.pfplaybackend.api.partyview.application.dto.ReactionDto;
import com.pfplaybackend.api.partyview.application.dto.result.PartyroomSetupResult;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PartyroomSetupQueryService {

    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackQueryService playbackQueryService;
    private final PlaybackReactionQueryService playbackReactionQueryService;
    private final UserProfileQueryPort userProfileQueryPort;

    @Transactional(readOnly = true)
    public PartyroomSetupResult getSetupInfo(PartyroomId partyroomId) {
        // Amplitude L1: stageType은 partyroom aggregate의 canonical 필드.
        // 클라이언트가 lobby 캐시 lookup 없이도 입장 시점에서 받을 수 있도록 응답에 포함.
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        List<CrewSetupDto> crews = getCrewsForSetup(partyroomId);
        DisplayDto display = getDisplayInfo();
        return new PartyroomSetupResult(partyroom.getStageType(), crews, display);
    }

    private List<CrewSetupDto> getCrewsForSetup(PartyroomId partyroomId) {
        List<CrewData> crews = partyroomQueryService.getActiveCrews(partyroomId);
        List<UserId> userIds = crews.stream().map(CrewData::getUserId).toList();
        Map<UserId, ProfileSettingDto> profileSettingMap = userProfileQueryPort.getUsersProfileSetting(userIds);

        return crews.stream().map(crew -> {
            UserId userId = crew.getUserId();
            return CrewSetupDto.from(crew, profileSettingMap.get(userId));
        }).toList();
    }

    private DisplayDto getDisplayInfo() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        ActivePartyroomDto activePartyroom = partyroomQueryService.getMyActivePartyroom(authContext.getUserId())
                .orElseThrow(() -> ExceptionCreator.create(CrewException.NOT_FOUND_ACTIVE_ROOM));
        boolean isPlaybackActivated = activePartyroom.playbackActivated();

        if (isPlaybackActivated) {
            PlaybackData playback = playbackQueryService.getPlaybackById(activePartyroom.currentPlaybackId());
            CrewId currentDjCrewId = activePartyroom.currentDjCrewId();
            CurrentDjDto currentDjDto = new CurrentDjDto(currentDjCrewId.getId());

            Optional<PlaybackReactionHistoryData> optional = playbackReactionQueryService.findPrevHistoryData(
                    new PlaybackId(playback.getId()), authContext.getUserId());
            PlaybackAggregationData aggregation = playbackQueryService.getPlaybackAggregationById(new PlaybackId(playback.getId()));
            AggregationDto aggregationDto = new AggregationDto(aggregation.getLikeCount(), aggregation.getDislikeCount(), aggregation.getGrabCount());
            ReactionDto reactionDto = ReactionDto.from(getHistory(optional), aggregationDto);
            PlaybackDto playbackDto = PlaybackDto.withEndTime(playback.getId(), playback.getLinkId(), playback.getName(), playback.getDuration(), playback.getThumbnailImage(), playback.getEndTime());
            return new DisplayDto(true, playbackDto, reactionDto, currentDjDto);
        } else {
            return new DisplayDto(false, null, null, null);
        }
    }

    private static ReactionHistoryDto getHistory(Optional<PlaybackReactionHistoryData> optional) {
        return optional.map(ReactionHistoryDto::from)
                .orElseGet(ReactionHistoryDto::empty);
    }
}
