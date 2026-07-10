package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
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
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
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
import java.util.List;
import java.util.Map;

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
