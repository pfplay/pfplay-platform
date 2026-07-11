package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pfplaybackend.api.common.exception.http.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 어드민 가상 DJ 서비스 통합 테스트 — config 적용→MANAGED+reconcile, drain 봇 제거, bulk 다중 룸.
 *
 * <p>fixture 패턴은 {@link VirtualCrewOrchestratorIT} 와 동일 — {@link UserProfileQueryPort} mock 으로
 * assertHasProfile 게이트만 통과시키고 나머지는 실 빈/실 DB(Testcontainers).
 */
@Transactional
class VirtualCrewAdminServiceIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private VirtualCrewAdminService adminService;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualCrewConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private BotPoolQueryRepository botPoolQueryRepository;
    @Autowired private TrackRepository trackRepository;

    @MockBean private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedGatesAndAvatar() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        // Chunk 3: provision 이 생성 즉시 assignRandomFromCatalog 로 변별 아바타를 부여하므로
        // 이 바디가 published 후보로 노출돼야 한다(standalone, 자체 아이콘 → face 의존 없음).
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    "https://example.test/icon_basic_001.png",
                    ObtainmentType.BASIC, 0, false, true, 60, 41, null);
            body.publish(null);
            avatarBodyResourceRepository.save(body);
        }
    }

    @AfterEach
    void clearContext() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("applyConfig MANAGED — config 가 MANAGED 로 전환되고 reconcile 로 봇이 T까지 채워진다")
    void applyConfig_MANAGED_transitions_and_fills() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();

        PartyroomVirtualCrewConfigData cfg = configRepository.findByPartyroomId(roomId).orElseThrow();
        assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(cfg.getTargetCount()).isEqualTo(2);
        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("drain — config OFF + 모든 봇 제거")
    void drain_removesAllBots_andTurnsOff() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();
        assertThat(activeBotDjCount(roomId)).isEqualTo(2);

        adminService.drain(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isZero();
        assertThat(configRepository.findByPartyroomId(roomId).orElseThrow().getStatus())
                .isEqualTo(VirtualCrewStatus.OFF);
    }

    @Test
    @DisplayName("drainResources — 봇 제거하되 config 는 MANAGED 유지")
    void drainResources_removesAllBots_keepsManaged() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();
        assertThat(activeBotDjCount(roomId)).isEqualTo(2);

        adminService.drainResources(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isZero();
        assertThat(configRepository.findByPartyroomId(roomId).orElseThrow().getStatus())
                .isEqualTo(VirtualCrewStatus.MANAGED);
    }

    @Test
    @DisplayName("revive — MANAGED 방을 목표로 재배치(placeToTarget), config 미변경")
    void revive_placesBotsToTarget() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();
        // 리소스 회수로 봇을 비운 뒤(config MANAGED 유지) revive 로 재배치 검증.
        adminService.drainResources(new PartyroomId(roomId));
        flushAndClear();
        assertThat(activeBotDjCount(roomId)).isZero();

        adminService.revive(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(configRepository.findByPartyroomId(roomId).orElseThrow().getStatus())
                .isEqualTo(VirtualCrewStatus.MANAGED);
    }

    @Test
    @DisplayName("applyBulk MANAGED — 여러 룸에 동일 config 적용 + 각 룸 reconcile")
    void applyBulk_appliesToMultipleRooms() {
        long roomA = seedRoom(5);
        long roomB = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(6);
        flushAndClear();

        adminService.applyBulk(List.of(roomA, roomB), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();

        assertThat(configRepository.findByPartyroomId(roomA).orElseThrow().getStatus())
                .isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(configRepository.findByPartyroomId(roomB).orElseThrow().getStatus())
                .isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(activeBotDjCount(roomA)).isEqualTo(2);
        assertThat(activeBotDjCount(roomB)).isEqualTo(2);
    }

    @Test
    @DisplayName("liveStatus — config + 현재 봇 수 반영")
    void liveStatus_reflectsConfigAndBotCount() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();

        VirtualCrewAdminService.LiveStatus status = adminService.liveStatus(new PartyroomId(roomId));
        assertThat(status.status()).isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(status.targetCount()).isEqualTo(2);
        assertThat(status.songPackId()).isEqualTo(packId);
        assertThat(status.currentBotDjCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("applyConfig MANAGED — djBotCount > targetCount 이면 INVALID_CONFIG(VCREW-007)")
    void applyConfig_djBotCountExceedsTarget_throwsInvalidConfig() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        flushAndClear();

        assertThatThrownBy(() -> adminService.applyConfig(
                new PartyroomId(roomId), VirtualCrewStatus.MANAGED, /*target*/2, /*djBotCount*/3, packId))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "VCREW-007");

        // config 는 변경/생성되지 않아야 한다(검증이 applyManaged 전에 던진다).
        assertThat(configRepository.findByPartyroomId(roomId)).isEmpty();
    }

    @Test
    @DisplayName("applyConfig MANAGED — djBotCount 가 필터 통과 트랙 수를 초과하면 DJ_COUNT_EXCEEDS_TRACKS(VCREW-014)")
    void applyConfig_djBotCountExceedsFilteredTracks_throwsDjCountExceedsTracks() {
        // seedRoom(5): 5분 제한 → seedSongPack 의 2트랙(2:00, 3:00) 모두 통과 → filteredTrackCount = 2
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        flushAndClear();

        assertThatThrownBy(() -> adminService.applyConfig(
                new PartyroomId(roomId), VirtualCrewStatus.MANAGED, /*target*/5, /*djBotCount*/5, packId))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "VCREW-014");

        assertThat(configRepository.findByPartyroomId(roomId)).isEmpty();
    }

    @Test
    @DisplayName("applyConfig 송팩 교체 — 기존 배치 봇이 새 팩 스냅샷으로 재복사된다 (무음 no-op 회귀 방지)")
    void applyConfig_packSwap_rebuildsBotSnapshots() {
        long roomId = seedRoom(5);
        Long packA = seedSongPackWith("vidA", "vidB");
        Long packB = seedSongPackWith("vidC", "vidD");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packA);
        flushAndClear();
        assertThat(activeBotPlaylistLinkIds(roomId)).containsExactlyInAnyOrder("vidA", "vidB");

        // 카운트 동일 + 송팩만 교체 → 종전엔 무음 no-op, 이제 replace 로 새 팩 반영
        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packB);
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(activeBotPlaylistLinkIds(roomId)).containsExactlyInAnyOrder("vidC", "vidD");
    }

    @Test
    @DisplayName("applyConfig 카운트만 변경 — 기존 봇 유지(전원 교체 아님), 추가분만 투입")
    void applyConfig_countOnly_keepsExistingBots() {
        long roomId = seedRoom(5);
        Long packId = seedSongPackWith("vidA", "vidB");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();
        List<UserId> before = activeBotUserIds(roomId);
        Set<Long> trackIdsBefore = activeBotPlaylistTrackIds(roomId);
        assertThat(trackIdsBefore).isNotEmpty();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 3, 2, packId);
        flushAndClear();

        assertThat(activeBotUserIds(roomId)).hasSize(3).containsAll(before); // 기존 봇 그대로 + 리스너 1 추가
        // 판별력 핵심: idle 봇 선택이 결정적(oldest-first)이라 userId 단언만으론 replace 로 흘러도 통과할 수 있다.
        // replace 였다면 re-copy 로 Track row 가 재생성돼 PK 가 바뀌고, reconcile 이면 기존 row 그대로다.
        assertThat(activeBotPlaylistTrackIds(roomId)).containsAll(trackIdsBefore);
    }

    @Test
    @DisplayName("replace() — 송팩 곡 구성 편집이 재배치로 반영된다")
    void replace_appliesEditedPackContents() {
        long roomId = seedRoom(5);
        Long packId = seedSongPackWith("vidA", "vidB");
        poolService.provision(4);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualCrewStatus.MANAGED, 2, 2, packId);
        flushAndClear();

        // 팩 내용 편집(트랙 추가) — 기존 봇에는 미반영 상태
        packTrackRepository.save(VirtualSongPackTrackData.create(packId, 3, "vidE", "Song vidE", "2:30", null));
        flushAndClear();

        adminService.replace(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(activeBotPlaylistLinkIds(roomId)).contains("vidE"); // 2봇 청크 분배에 vidE 포함
    }

    // ── fixtures (mirror VirtualCrewOrchestratorIT) ──

    private long seedRoom(int playbackTimeLimitMinutes) {
        PartyroomData p = PartyroomData.create(
                "vcrew", "intro", LinkDomain.of("link-vcrew-" + System.nanoTime()),
                PlaybackTimeLimit.ofMinutes(playbackTimeLimitMinutes), StageType.GENERAL,
                new UserId(8000L));
        long id = partyroomRepository.saveAndFlush(p).getId();
        PartyroomId pid = new PartyroomId(id);
        entityManager.persist(PartyroomPlaybackData.createFor(pid));
        entityManager.persist(DjQueueData.createFor(pid));
        entityManager.flush();
        return id;
    }

    private Long seedSongPack() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Pack-" + System.nanoTime(), "IT"));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "vidA", "Song A", "2:00", null));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, "vidB", "Song B", "3:00", null));
        return pack.getId();
    }

    private Long seedSongPackWith(String vid1, String vid2) {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Pack-" + System.nanoTime(), "IT"));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, vid1, "Song " + vid1, "2:00", null));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, vid2, "Song " + vid2, "3:00", null));
        return pack.getId();
    }

    /** 룸의 활성 봇 crew 전원의 개인 플레이리스트 트랙 linkId 합집합(청크 분배라 개별 봇 단위로 나뉘어질 수 있음). */
    private Set<String> activeBotPlaylistLinkIds(long roomId) {
        List<UserId> bots = botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(new PartyroomId(roomId));
        Set<String> linkIds = new HashSet<>();
        for (UserId bot : bots) {
            Long playlistId = poolService.playlistIdOf(bot);
            for (TrackData track : trackRepository.findAllByPlaylistId(new PlaylistId(playlistId))) {
                linkIds.add(track.getLinkId());
            }
        }
        return linkIds;
    }

    /** 룸의 활성 봇 crew userId 목록(역할 무관). */
    private List<UserId> activeBotUserIds(long roomId) {
        return botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(new PartyroomId(roomId));
    }

    /**
     * 룸의 활성 봇 crew 전원의 개인 플레이리스트 Track row PK(id) 집합.
     * replace(re-copy) 는 Track row 를 재생성해 PK 를 바꾸고, reconcile 은 기존 row 를 보존한다 —
     * count-only 경로가 reconcile 로 흘렀는지 판별하는 지문(fingerprint)으로 쓴다.
     */
    private Set<Long> activeBotPlaylistTrackIds(long roomId) {
        List<UserId> bots = botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(new PartyroomId(roomId));
        Set<Long> trackIds = new HashSet<>();
        for (UserId bot : bots) {
            Long playlistId = poolService.playlistIdOf(bot);
            for (TrackData track : trackRepository.findAllByPlaylistId(new PlaylistId(playlistId))) {
                trackIds.add(track.getId());
            }
        }
        return trackIds;
    }

    private long activeBotDjCount(long roomId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM dj d " +
                        "JOIN crew c ON c.crew_id = d.crew_id " +
                        "JOIN user_account u ON u.user_id = c.user_id " +
                        "WHERE d.partyroom_id = :rid AND c.is_active = 1 AND u.is_dummy = 1")
                .setParameter("rid", roomId)
                .getSingleResult();
        return n.longValue();
    }
}
