package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.AddedTrackInfo;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.exception.ReactionException;
import com.pfplaybackend.api.party.domain.model.ReactionPostProcessResult;
import com.pfplaybackend.api.party.domain.model.ReactionState;
import com.pfplaybackend.api.party.domain.service.PlaybackReactionDomainService;
import com.pfplaybackend.api.party.application.dto.playback.AddedTrackDto;
import com.pfplaybackend.api.party.application.dto.playback.ReactionHistoryDto;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlaybackReactionCommandService {

    private final PlaybackReactionHistoryRepository playbackReactionHistoryRepository;
    private final PlaybackReactionDomainService playbackReactionDomainService;
    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackReactionPostProcessCommandService playbackReactionPostProcessCommandService;
    private final PlaybackReactionQueryService playbackReactionQueryService;

    @Transactional
    public ReactionHistoryDto reactToCurrentPlayback(PartyroomId partyroomId, ReactionType reactionType) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        if(AuthorityTier.GT.equals(authContext.getAuthorityTier()) && reactionType.equals(ReactionType.GRAB)) {
            throw ExceptionCreator.create(ReactionException.INVALID_REACTION);
        }

        ActivePartyroomDto myActivePartyroom = partyroomQueryService.getMyActivePartyroom().orElseThrow();
        PlaybackId playbackId = myActivePartyroom.currentPlaybackId();
        PlaybackReactionHistoryData historyData = getValidReactionHistoryData(authContext, playbackId);
        ReactionState existingState = getExistingState(historyData);
        ReactionState targetState = getTargetState(existingState, reactionType);

        ReactionPostProcessResult reactionPostProcessDto = executeProcess(historyData, existingState, targetState);
        Optional<CrewData> optional = partyroomQueryService.getCrewByUserId(partyroomId, authContext.getUserId());
        CrewData crew = optional.orElseThrow();
        AddedTrackInfo addedTrack = playbackReactionPostProcessCommandService.postProcess(
                reactionPostProcessDto, reactionType, partyroomId, playbackId, new CrewId(crew.getId()));
        return ReactionHistoryDto.from(targetState, toAddedTrackDto(addedTrack));
    }

    private AddedTrackDto toAddedTrackDto(AddedTrackInfo info) {
        return info == null ? null : new AddedTrackDto(info.trackId(), info.playlistId());
    }

    private PlaybackReactionHistoryData getValidReactionHistoryData(AuthContext authContext, PlaybackId playbackId) {
        return playbackReactionQueryService.findPrevHistoryData(playbackId, authContext.getUserId())
                .orElseGet(() -> new PlaybackReactionHistoryData(authContext.getUserId(), playbackId));
    }

    private ReactionState getExistingState(PlaybackReactionHistoryData historyData) {
        return historyData.getId() != null
                ? playbackReactionDomainService.getReactionStateByHistory(historyData)
                : ReactionState.createBaseState();
    }

    private ReactionState getTargetState(ReactionState existingState, ReactionType reactionType) {
        return playbackReactionDomainService.getTargetReactionState(existingState, reactionType);
    }

    private ReactionPostProcessResult executeProcess(PlaybackReactionHistoryData historyData,
                                                  ReactionState existingState,
                                                  ReactionState targetState) {
        playbackReactionHistoryRepository.save(historyData.applyReactionState(targetState));
        return playbackReactionDomainService.determinePostProcessing(existingState, targetState);
    }
}
