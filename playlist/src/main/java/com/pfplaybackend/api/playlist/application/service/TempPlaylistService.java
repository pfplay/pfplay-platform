package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-DJ(#331) one-shot 곡 저장용 TEMP 플리 준비.
 * per-user 1개를 재사용하며 호출마다 리셋(전곡 삭제) 후 선택 곡 1개만 삽입한다(spec §3-2 step3~5, 결정6).
 * 재생 커서(lastPlayedTrackId)는 리셋하지 않는다 — 가리키던 트랙이 삭제되면
 * peekTracksFromCursor 가 자연 순서로 fall-back 하므로 무해(spec §4).
 */
@Service
@RequiredArgsConstructor
public class TempPlaylistService {

    private final PlaylistAggregatePort aggregatePort;

    @Transactional
    public Long prepareOneShotPlaylist(UserId userId, AddTrackCommand command) {
        PlaylistData temp = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.TEMP).stream()
                .findFirst()
                .orElseGet(() -> aggregatePort.savePlaylist(
                        PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, userId)));

        aggregatePort.deleteAllTracksByPlaylist(temp.getId());

        TrackData track = TrackData.builder()
                .playlistId(new PlaylistId(temp.getId()))
                .name(command.name())
                .linkId(command.linkId())
                .duration(Duration.fromString(command.duration()))
                .orderNumber(1)
                .thumbnailImage(command.thumbnailImage())
                .build();
        aggregatePort.saveTrack(track);
        return temp.getId();
    }
}
