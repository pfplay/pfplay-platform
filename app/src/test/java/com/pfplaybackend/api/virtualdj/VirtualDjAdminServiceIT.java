package com.pfplaybackend.api.virtualdj;

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
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjAdminService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 어드민 가상 DJ 서비스 통합 테스트 — config 적용→MANAGED+reconcile, drain 봇 제거, bulk 다중 룸, freeze.
 *
 * <p>fixture 패턴은 {@link VirtualDjOrchestratorIT} 와 동일 — {@link UserProfileQueryPort} mock 으로
 * assertHasProfile 게이트만 통과시키고 나머지는 실 빈/실 DB(Testcontainers).
 */
@Transactional
class VirtualDjAdminServiceIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private VirtualDjAdminService adminService;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualDjConfigRepository configRepository;
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
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            avatarBodyResourceRepository.save(AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    "https://example.test/icon_basic_001.png",
                    ObtainmentType.BASIC, 0, false, true, 60, 41, null));
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

        adminService.applyConfig(new PartyroomId(roomId), VirtualDjStatus.MANAGED, 2, 1, packId);
        flushAndClear();

        PartyroomVirtualDjConfigData cfg = configRepository.findByPartyroomId(roomId).orElseThrow();
        assertThat(cfg.getStatus()).isEqualTo(VirtualDjStatus.MANAGED);
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

        adminService.applyConfig(new PartyroomId(roomId), VirtualDjStatus.MANAGED, 2, 1, packId);
        flushAndClear();
        assertThat(activeBotDjCount(roomId)).isEqualTo(2);

        adminService.drain(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isZero();
        assertThat(configRepository.findByPartyroomId(roomId).orElseThrow().getStatus())
                .isEqualTo(VirtualDjStatus.OFF);
    }

    @Test
    @DisplayName("freeze — status FROZEN 전환, 봇 수 불변")
    void freeze_setsFrozen() {
        long roomId = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(3);
        flushAndClear();

        adminService.applyConfig(new PartyroomId(roomId), VirtualDjStatus.MANAGED, 2, 1, packId);
        flushAndClear();
        long botsBefore = activeBotDjCount(roomId);

        adminService.freeze(new PartyroomId(roomId));
        flushAndClear();

        assertThat(configRepository.findByPartyroomId(roomId).orElseThrow().getStatus())
                .isEqualTo(VirtualDjStatus.FROZEN);
        assertThat(activeBotDjCount(roomId)).isEqualTo(botsBefore);
    }

    @Test
    @DisplayName("applyBulk MANAGED — 여러 룸에 동일 config 적용 + 각 룸 reconcile")
    void applyBulk_appliesToMultipleRooms() {
        long roomA = seedRoom(5);
        long roomB = seedRoom(5);
        Long packId = seedSongPack();
        poolService.provision(6);
        flushAndClear();

        adminService.applyBulk(List.of(roomA, roomB), VirtualDjStatus.MANAGED, 2, 1, packId);
        flushAndClear();

        assertThat(configRepository.findByPartyroomId(roomA).orElseThrow().getStatus())
                .isEqualTo(VirtualDjStatus.MANAGED);
        assertThat(configRepository.findByPartyroomId(roomB).orElseThrow().getStatus())
                .isEqualTo(VirtualDjStatus.MANAGED);
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

        adminService.applyConfig(new PartyroomId(roomId), VirtualDjStatus.MANAGED, 2, 1, packId);
        flushAndClear();

        VirtualDjAdminService.LiveStatus status = adminService.liveStatus(new PartyroomId(roomId));
        assertThat(status.status()).isEqualTo(VirtualDjStatus.MANAGED);
        assertThat(status.targetCount()).isEqualTo(2);
        assertThat(status.songPackId()).isEqualTo(packId);
        assertThat(status.currentBotDjCount()).isEqualTo(2);
    }

    // ── fixtures (mirror VirtualDjOrchestratorIT) ──

    private long seedRoom(int playbackTimeLimitMinutes) {
        PartyroomData p = PartyroomData.create(
                "vdj", "intro", LinkDomain.of("link-vdj-" + System.nanoTime()),
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
