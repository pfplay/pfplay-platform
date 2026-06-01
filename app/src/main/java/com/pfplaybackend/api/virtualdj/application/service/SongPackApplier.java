package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 송 팩의 트랙을 봇 playlist 에 복사하는 서비스.
 *
 * <p>Chunk 4 오케스트레이터가 봇을 룸에 투입하기 전에 호출한다.
 * 기존 봇 playlist 트랙을 완전히 비우고, 송 팩에서 룸의
 * {@code roomPlaybackTimeLimitMinutes} 이하인 트랙만 순서대로 복사한다.
 *
 * <p>ArchUnit: {@code *Orchestrator*} 클래스가 아니므로 persistence 직접 접근 허용.
 */
@Service
@RequiredArgsConstructor
public class SongPackApplier {

    private final VirtualUserPoolService virtualUserPoolService;
    private final VirtualSongPackTrackRepository songPackTrackRepository;
    private final PlaylistRepository playlistRepository;
    private final TrackRepository trackRepository;

    /**
     * 봇의 DJ playlist 에 송 팩 트랙을 복사한다.
     *
     * @param botUserId                      봇 사용자 id
     * @param songPackId                     적용할 송 팩 id
     * @param roomPlaybackTimeLimitMinutes   룸의 재생 시간 제한 (분). 0 이하 = unlimited.
     */
    @Transactional
    public void applyToBot(UserId botUserId, Long songPackId, int roomPlaybackTimeLimitMinutes) {
        // 1. 봇 playlist id 조회
        Long playlistId = virtualUserPoolService.playlistIdOf(botUserId);
        PlaylistData playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bot playlist not found for playlistId=" + playlistId + " — bot not provisioned correctly"));

        // 2. 기존 트랙 전체 삭제 (봇 playlist 소속만)
        trackRepository.deleteAllByPlaylistIdValue(playlistId);

        // 3. 송 팩 트랙 (정렬됨) 필터 후 복사
        List<VirtualSongPackTrackData> packTracks =
                songPackTrackRepository.findBySongPackIdOrderByOrderNumberAsc(songPackId);

        PlaybackTimeLimit timeLimit = PlaybackTimeLimit.ofMinutes(roomPlaybackTimeLimitMinutes);

        List<TrackData> toSave = new ArrayList<>();
        int orderCounter = 1;
        for (VirtualSongPackTrackData packTrack : packTracks) {
            Duration duration = Duration.fromString(packTrack.getDuration());
            if (timeLimit.exceedsDuration(duration)) {
                continue; // 초과 트랙 제외
            }
            toSave.add(TrackData.builder()
                    .playlistId(new PlaylistId(playlistId))
                    .name(packTrack.getName())
                    .linkId(packTrack.getLinkId())
                    .duration(duration)
                    .orderNumber(orderCounter++)
                    .thumbnailImage(packTrack.getThumbnailImage())
                    .build());
        }
        trackRepository.saveAll(toSave);

        // 4. playlist 에 sourceSongPackId 바인딩
        playlist.bindSongPackSource(songPackId);
        playlistRepository.save(playlist);
    }
}
