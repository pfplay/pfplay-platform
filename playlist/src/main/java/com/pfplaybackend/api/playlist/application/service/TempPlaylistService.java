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

import java.util.List;

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
        List<PlaylistData> temps = aggregatePort.findPlaylistsByOwnerAndType(userId, PlaylistType.TEMP);
        PlaylistData temp = temps.stream()
                .findFirst()
                .orElseGet(() -> aggregatePort.savePlaylist(
                        PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, userId)));

        // 같은 유저의 동시 더블서밋 시 find-or-create 는 read-then-write 레이스라 TEMP 가 2행 이상
        // 생길 수 있다(per-user 유니크 제약 없음 — MySQL 은 partial index 불가). blast radius 는
        // 숨김 행 고아화에 한정된다(TEMP 는 목록/단건 조회에서 제외되고, enqueue 는 isDjRegistered
        // 가드가 큐 중복을 차단). 여기서 첫 행만 남기고 정리해 다음 호출이 자가치유한다(opportunistic dedup).
        if (temps.size() > 1) {
            List<Long> extraIds = temps.subList(1, temps.size()).stream()
                    .map(PlaylistData::getId)
                    .toList();
            extraIds.forEach(aggregatePort::deleteAllTracksByPlaylist);
            aggregatePort.deletePlaylistsByIds(extraIds);
        }

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
