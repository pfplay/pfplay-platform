package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.AddedTrackInfo;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.UserActivityPort;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.enums.MotionType;
import com.pfplaybackend.api.party.domain.enums.ReactionType;
import com.pfplaybackend.api.party.domain.event.ReactionAggregationChangedEvent;
import com.pfplaybackend.api.party.domain.event.ReactionMotionChangedEvent;
import com.pfplaybackend.api.party.domain.model.ReactionPostProcessResult;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlaybackReactionPostProcessCommandService {
    private final PlaybackQueryService playbackQueryService;
    private final PlaybackCommandService playbackCommandService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlaylistCommandPort playlistCommandPort;
    private final UserActivityPort userActivityPort;

    /**
     * @return AddedTrackInfo when this call materialised a new GRABLIST entry, otherwise null.
     */
    public AddedTrackInfo postProcess(ReactionPostProcessResult postProcessDto, ReactionType reactionType, PartyroomId partyroomId, PlaybackId playbackId, CrewId crewId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        PlaybackData playback = playbackQueryService.getPlaybackById(playbackId);
        AddedTrackInfo addedTrack = null;
        if(postProcessDto.grabStatusChanged()) {
            addedTrack = grabTrack(authContext.getUserId(), playback);
        }
        if(postProcessDto.djActivityScoreChanged()) {
            updateDjActivityScore(playback.getUserId(), postProcessDto.deltaScore());
        }
        if(postProcessDto.aggregationChanged()) {
            PlaybackAggregationData aggregation = playbackCommandService.updatePlaybackAggregation(new PlaybackId(playback.getId()), postProcessDto.deltaRecord());
            publishAggregationChangedEvent(partyroomId, aggregation);
        }
        publishMotionChangedEvent(partyroomId, reactionType, postProcessDto.determinedMotionType(), crewId);
        return addedTrack;
    }

    public void publishMotionChangedEvent(PartyroomId partyroomId, ReactionType reactionType, MotionType motionType, CrewId crewId) {
        eventPublisher.publishEvent(new ReactionMotionChangedEvent(partyroomId, reactionType, motionType, crewId));
    }

    public void updateDjActivityScore(UserId djUserId, int deltaScore) {
        userActivityPort.updateDjPointScore(djUserId, deltaScore);
    }

    public void publishAggregationChangedEvent(PartyroomId partyroomId, PlaybackAggregationData aggregation) {
        eventPublisher.publishEvent(new ReactionAggregationChangedEvent(
                partyroomId, aggregation.getLikeCount(), aggregation.getDislikeCount(), aggregation.getGrabCount()));
    }

    public AddedTrackInfo grabTrack(UserId userId, PlaybackData playback) {
        // TODO 이미 동일한 LinkId를 보유하고 있다면 예외 발생
        return playlistCommandPort.grabTrack(userId, playback.getLinkId());
    }
}
