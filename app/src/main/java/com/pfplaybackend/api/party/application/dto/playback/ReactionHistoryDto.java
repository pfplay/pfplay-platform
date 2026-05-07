package com.pfplaybackend.api.party.application.dto.playback;

import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.model.ReactionState;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "재생 리액션 히스토리")
public record ReactionHistoryDto(
        @Schema(description = "좋아요 여부", example = "false") boolean isLiked,
        @Schema(description = "싫어요 여부", example = "false") boolean isDisliked,
        @Schema(description = "그랩 여부", example = "false") boolean isGrabbed,
        @Schema(description = "GRAB 성공 시 사용자 GRABLIST에 신규 추가된 트랙 정보 (LIKE/DISLIKE/이미 그랩 상태에서 토글 off → null)", nullable = true)
        AddedTrackDto addedTrack
) {
    public static ReactionHistoryDto empty() {
        return new ReactionHistoryDto(false, false, false, null);
    }

    public static ReactionHistoryDto from(ReactionState state) {
        return new ReactionHistoryDto(state.liked(), state.disliked(), state.grabbed(), null);
    }

    public static ReactionHistoryDto from(ReactionState state, AddedTrackDto addedTrack) {
        return new ReactionHistoryDto(state.liked(), state.disliked(), state.grabbed(), addedTrack);
    }

    public static ReactionHistoryDto from(PlaybackReactionHistoryData data) {
        return new ReactionHistoryDto(data.isLiked(), data.isDisliked(), data.isGrabbed(), null);
    }
}
