package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackReactionCommandService {

    private final PlaybackReactionHistoryRepository playbackReactionHistoryRepository;
    private final PlaybackReactionDomainService playbackReactionDomainService;
    private final PartyroomQueryService partyroomQueryService;
    private final PlaybackReactionPostProcessCommandService playbackReactionPostProcessCommandService;

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

    /**
     * E/#6 (#231) race 차단: history 행을 비관적 쓰기 락으로 확보한다.
     *
     * <p>행이 이미 존재하면 {@code SELECT ... FOR UPDATE} 가 같은 (user, playback) 의
     * read→delta→aggregation-UPDATE 시퀀스를 직렬화한다(락 순서 history→aggregation 단방향).
     *
     * <p>첫 반응(행 미존재)이면 base 행을 즉시 INSERT+flush 해 물리적으로 존재시키고 그 행에
     * 락을 잡는다. 동시 첫-INSERT 의 패자는 unique 제약 {@code uk_reaction_user_playback}
     * 위반({@link DataIntegrityViolationException})을 만나는데, 이를 원천에서 잡아 WARN 로
     * 관측 가능하게 남기고 {@link ReactionException#REACTION_CONFLICT_RETRY} 로 재던져
     * 깔끔한 409 로 매핑한다(원시 JDBC/제약 메시지 누출·HTTP 500·무로그 손실 방지). 패자의
     * delta 는 적용되지 않으므로 카운터는 일관 유지되며, 클라이언트 재시도 시 이제 존재하는
     * 행에 락을 잡아 정상 처리된다(연타 첫-INSERT 손실은 카운터 무결성 우선 정책상 허용).
     */
    private PlaybackReactionHistoryData getValidReactionHistoryData(AuthContext authContext, PlaybackId playbackId) {
        Optional<PlaybackReactionHistoryData> locked = playbackReactionHistoryRepository
                .findByPlaybackIdAndUserIdForUpdate(playbackId, authContext.getUserId());
        if (locked.isPresent()) {
            return locked.get();
        }
        return insertBaseHistoryRow(authContext.getUserId(), playbackId);
    }

    /**
     * 첫 반응 base 행을 INSERT+flush. 동시 첫-INSERT 패자는 unique 제약 위반으로
     * {@link DataIntegrityViolationException} 을 받는데, WARN 로 남기고 409
     * {@link ReactionException#REACTION_CONFLICT_RETRY} 로 변환한다(클라이언트 재시도 유도).
     */
    private PlaybackReactionHistoryData insertBaseHistoryRow(UserId userId, PlaybackId playbackId) {
        try {
            return playbackReactionHistoryRepository.saveAndFlush(
                    new PlaybackReactionHistoryData(userId, playbackId));
        } catch (DataIntegrityViolationException e) {
            log.warn("[getValidReactionHistoryData] CONCURRENT_FIRST_INSERT_LOST - " +
                     "playbackId={}, userId={} - reaction dropped, client should retry",
                     playbackId, userId);
            throw ExceptionCreator.create(ReactionException.REACTION_CONFLICT_RETRY);
        }
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
