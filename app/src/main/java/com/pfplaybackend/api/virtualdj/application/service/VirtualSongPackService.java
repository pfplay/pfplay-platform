package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.dto.command.AddPackTrackCommand;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 송 팩(VirtualSongPack) CRUD 서비스.
 *
 * <p>팩 빌드 시점 duration 검증: playbackTimeLimit 은 방마다 다를 수 있으나(0=unlimited 포함),
 * 어드민이 팩을 구성할 때는 방의 limit 을 알 수 없으므로 시스템 허용 상한인
 * {@link #MAX_PLAYBACK_LIMIT_MINUTES 60분}을 보수적 상한으로 사용한다.
 * 이 상한은 {@code UpdatePartyroomMetaRequest}의 {@code @Max(60)} 검증과 일치한다.
 *
 * <p>적용 시점(SongPackApplier)에서 실제 방의 playbackTimeLimit 으로 재필터링한다.
 */
@Service
@RequiredArgsConstructor
public class VirtualSongPackService {

    /**
     * 송 팩 트랙의 보수적 재생시간 상한 (분).
     * 출처: {@code UpdatePartyroomMetaRequest#playbackTimeLimit @Max(60)}.
     */
    static final int MAX_PLAYBACK_LIMIT_MINUTES = 60;

    private final VirtualSongPackRepository packRepository;
    private final VirtualSongPackTrackRepository trackRepository;
    private final PartyroomVirtualDjConfigRepository configRepository;
    private final PlaylistRepository playlistRepository;

    // ── Pack CRUD ──

    @Transactional
    public Long createPack(String name, String description) {
        if (packRepository.existsByName(name)) {
            throw ExceptionCreator.create(VirtualDjException.SONG_PACK_DUPLICATE_NAME);
        }
        VirtualSongPackData saved = packRepository.save(VirtualSongPackData.create(name, description));
        return saved.getId();
    }

    @Transactional
    public void renamePack(Long packId, String name) {
        VirtualSongPackData pack = packRepository.findById(packId)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.SONG_PACK_NOT_FOUND));
        if (!name.equals(pack.getName()) && packRepository.existsByName(name)) {
            throw ExceptionCreator.create(VirtualDjException.SONG_PACK_DUPLICATE_NAME);
        }
        pack.rename(name);
        packRepository.save(pack);
    }

    @Transactional
    public void deletePack(Long packId) {
        VirtualSongPackData pack = packRepository.findById(packId)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.SONG_PACK_NOT_FOUND));

        // Delete guard: config 참조 여부 — song_pack_id 인덱스 활용 (full-scan findAll 제거).
        if (configRepository.existsBySongPackId(packId)) {
            throw ExceptionCreator.create(VirtualDjException.SONG_PACK_IN_USE);
        }

        // Delete guard: playlist 참조 여부
        if (playlistRepository.existsBySourceSongPackId(packId)) {
            throw ExceptionCreator.create(VirtualDjException.SONG_PACK_IN_USE);
        }

        List<VirtualSongPackTrackData> tracks = trackRepository.findBySongPackIdOrderByOrderNumberAsc(packId);
        trackRepository.deleteAll(tracks);
        packRepository.delete(pack);
    }

    // ── Track CRUD ──

    @Transactional
    public Long addTrack(Long packId, AddPackTrackCommand cmd) {
        packRepository.findById(packId)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.SONG_PACK_NOT_FOUND));

        // duration 보수적 상한 검증 (60분)
        Duration duration = Duration.fromString(cmd.duration());
        if (PlaybackTimeLimit.ofMinutes(MAX_PLAYBACK_LIMIT_MINUTES).exceedsDuration(duration)) {
            throw ExceptionCreator.create(VirtualDjException.TRACK_EXCEEDS_PLAYBACK_LIMIT);
        }

        // order_number: 기존 트랙의 마지막 order_number + 1
        List<VirtualSongPackTrackData> existing = trackRepository.findBySongPackIdOrderByOrderNumberAsc(packId);
        int nextOrder = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1).getOrderNumber() + 1;

        VirtualSongPackTrackData track = VirtualSongPackTrackData.create(
                packId, nextOrder, cmd.linkId(), cmd.name(), cmd.duration(), cmd.thumbnailImage());
        VirtualSongPackTrackData saved = trackRepository.save(track);
        return saved.getId();
    }

    @Transactional
    public void removeTrack(Long packId, Long packTrackId) {
        packRepository.findById(packId)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.SONG_PACK_NOT_FOUND));
        VirtualSongPackTrackData track = trackRepository.findById(packTrackId)
                .filter(t -> packId.equals(t.getSongPackId()))
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.PACK_TRACK_NOT_FOUND));
        trackRepository.delete(track);
    }
}
