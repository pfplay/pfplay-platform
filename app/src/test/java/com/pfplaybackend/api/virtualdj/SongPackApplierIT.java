package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.service.SongPackApplier;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SongPackApplierIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private SongPackApplier applier;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository trackRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private TrackRepository playlistTrackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @BeforeEach
    void seedDefaultAvatarBody() {
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) != null) {
            return;
        }
        // Chunk 3: provision 이 생성 즉시 assignRandomFromCatalog 로 변별 아바타를 부여하므로
        // 이 바디가 published 후보로 노출돼야 한다(standalone, 자체 아이콘 → face 의존 없음).
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "ava_body_basic_001", DEFAULT_BODY_URI,
                "https://example.test/icon_basic_001.png",
                ObtainmentType.BASIC, 0, false, true, 60, 41, null);
        body.publish(null);
        avatarBodyResourceRepository.save(body);
    }

    @Test
    @DisplayName("applyToBot — in-limit 트랙만 복사되고 sourceSongPackId 가 바인딩된다")
    void applyToBot_인리밋_트랙만_복사() {
        // 봇 프로비저닝
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        // 송 팩 생성 (3 트랙: 2분 / 4분 / 6분)
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Test Pack", "IT 테스트용"));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "vid1", "Song A", "2:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, "vid2", "Song B", "4:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 3, "vid3", "Song C", "6:00", null));
        flushAndClear();

        // playbackTimeLimit = 5분 → 6분 트랙(Song C) 제외
        applier.applyToBot(botUserId, pack.getId(), 5);

        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);
        PlaylistData playlist = playlistRepository.findById(playlistId).orElseThrow();

        // source_song_pack_id 바인딩 확인
        assertThat(playlist.getSourceSongPackId()).isEqualTo(pack.getId());

        // 복사된 트랙 목록 확인
        List<TrackData> tracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream()
                .sorted(Comparator.comparingInt(TrackData::getOrderNumber))
                .toList();

        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).getLinkId()).isEqualTo("vid1");
        assertThat(tracks.get(0).getOrderNumber()).isEqualTo(1);
        assertThat(tracks.get(1).getLinkId()).isEqualTo("vid2");
        assertThat(tracks.get(1).getOrderNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("applyToBot — 기존 트랙이 있으면 clear 후 새 트랙으로 교체된다")
    void applyToBot_기존트랙_clear_후_교체() {
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);

        // 봇 playlist 에 기존 트랙 삽입
        PlaylistData playlist = playlistRepository.findById(playlistId).orElseThrow();
        playlistTrackRepository.save(TrackData.builder()
                .playlistId(new PlaylistId(playlistId))
                .name("Old Song")
                .linkId("oldVid")
                .duration(com.pfplaybackend.api.common.domain.value.Duration.fromString("3:00"))
                .orderNumber(1)
                .thumbnailImage(null)
                .build());
        flushAndClear();

        // 새 송 팩 생성 (1 트랙)
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("New Pack", "교체 테스트"));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "newVid", "New Song", "2:00", null));
        flushAndClear();

        applier.applyToBot(botUserId, pack.getId(), 10);
        flushAndClear();

        List<TrackData> replacedTracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId));
        assertThat(replacedTracks).hasSize(1);
        assertThat(replacedTracks.get(0).getLinkId()).isEqualTo("newVid");
    }

    @Test
    @DisplayName("applyToBot — roomPlaybackTimeLimit=0(unlimited)이면 모든 트랙 포함")
    void applyToBot_unlimited_모든트랙_포함() {
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("All Pack", "무제한 테스트"));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "v1", "Song 1", "30:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, "v2", "Song 2", "59:00", null));
        flushAndClear();

        // 0 = unlimited
        applier.applyToBot(botUserId, pack.getId(), 0);
        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);
        List<TrackData> unlimitedTracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId));

        assertThat(unlimitedTracks).hasSize(2);
    }

    @Test
    @DisplayName("applyChunkToBot — 6트랙/djCount=3/slot=1 → 중간 조각(3·4번째)만 복사된다")
    void applyChunkToBot_중간_조각만_복사() {
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        // 6 트랙 모두 시간제한 이내 (2분)
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Chunk Pack", "분배 테스트"));
        for (int i = 1; i <= 6; i++) {
            trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), i, "vid" + i, "Song " + i, "2:00", null));
        }
        flushAndClear();

        // 6트랙 / djCount 3 → base=2, rem=0 → slot0=[0,1] slot1=[2,3] slot2=[4,5] (0-based)
        // slot1 = 3·4번째 트랙 = vid3, vid4
        applier.applyChunkToBot(botUserId, pack.getId(), 5, 1, 3);
        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);
        PlaylistData playlist = playlistRepository.findById(playlistId).orElseThrow();
        assertThat(playlist.getSourceSongPackId()).isEqualTo(pack.getId());

        List<TrackData> tracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream()
                .sorted(Comparator.comparingInt(TrackData::getOrderNumber))
                .toList();

        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).getName()).isEqualTo("Song 3");
        assertThat(tracks.get(0).getLinkId()).isEqualTo("vid3");
        assertThat(tracks.get(0).getOrderNumber()).isEqualTo(1);
        assertThat(tracks.get(1).getName()).isEqualTo("Song 4");
        assertThat(tracks.get(1).getLinkId()).isEqualTo("vid4");
        assertThat(tracks.get(1).getOrderNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("applyChunkToBot — 6트랙/djCount=3/slot=0 → 첫 조각(1·2번째)만 복사된다")
    void applyChunkToBot_첫_조각만_복사() {
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Chunk Pack 0", "분배 테스트"));
        for (int i = 1; i <= 6; i++) {
            trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), i, "vid" + i, "Song " + i, "2:00", null));
        }
        flushAndClear();

        applier.applyChunkToBot(botUserId, pack.getId(), 5, 0, 3);
        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);
        List<TrackData> tracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream()
                .sorted(Comparator.comparingInt(TrackData::getOrderNumber))
                .toList();

        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).getLinkId()).isEqualTo("vid1");
        assertThat(tracks.get(1).getLinkId()).isEqualTo("vid2");
    }

    @Test
    @DisplayName("applyChunkToBot — 7트랙/djCount=3/slot=0 → 나머지 앞쪽 배치로 3트랙(1·2·3번째)")
    void applyChunkToBot_나머지_앞쪽_배치() {
        List<UserId> bots = poolService.provision(1);
        UserId botUserId = bots.get(0);
        flushAndClear();

        // 7 트랙 / djCount 3 → base=2, rem=1 → slot0=[0,1,2](3개) slot1=[3,4] slot2=[5,6]
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Chunk Pack 7", "홀수 분배 테스트"));
        for (int i = 1; i <= 7; i++) {
            trackRepository.save(VirtualSongPackTrackData.create(pack.getId(), i, "vid" + i, "Song " + i, "2:00", null));
        }
        flushAndClear();

        applier.applyChunkToBot(botUserId, pack.getId(), 5, 0, 3);
        flushAndClear();

        Long playlistId = poolService.playlistIdOf(botUserId);
        List<TrackData> tracks = playlistTrackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream()
                .sorted(Comparator.comparingInt(TrackData::getOrderNumber))
                .toList();

        assertThat(tracks).hasSize(3);
        assertThat(tracks.get(0).getLinkId()).isEqualTo("vid1");
        assertThat(tracks.get(1).getLinkId()).isEqualTo("vid2");
        assertThat(tracks.get(2).getLinkId()).isEqualTo("vid3");
    }
}
