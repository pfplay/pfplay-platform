package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Chunk 5 이벤트 반응 통합 테스트 — 실 AFTER_COMMIT 경로로 listener → reconcile wiring 을 검증한다.
 *
 * <p><b>commit 경계 처리:</b> {@code @TransactionalEventListener(AFTER_COMMIT)} 는 커밋이 일어나야
 * 발화하므로 클래스-레벨 {@code @Transactional} 로는 트리거되지 않는다(롤백 only). 따라서 이 IT 는
 * 비-{@code @Transactional} 로 두고 {@link TransactionTemplate} 안에서 도메인 이벤트를 publish 하여
 * 실제 커밋 → listener 발화 경로를 그대로 탄다. 정리는 명시적으로 한다(UserActivityLogListener*IT 패턴).
 *
 * <p>고정 2역할 모델에서 크루 입퇴장·DJ 큐 변경에 의한 런타임 재등록 리스너는 제거됐으므로, 남은
 * 리스너는 {@code onTerminated}(룸 종료 시 config OFF) 뿐이며 이 IT 도 그 경로만 검증한다.
 */
class VirtualCrewEventListenerIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualCrewConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    @MockBean private UserProfileQueryPort userProfileQueryPort;

    private Long roomId;
    /** FK fk_paa_administrator → administrator. terminate 이벤트 actor. */
    private Long adminId;

    @BeforeEach
    void seed() {
        userAccountRepository.saveAndFlush(
                UserAccountData.createForLocal(new UserId(990900L), "vdj-evt-admin@x", "h"));
        adminId = administratorRepository
                .saveAndFlush(AdministratorData.createSuperAdmin(990900L)).getAdministratorId();

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

        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = PartyroomData.create(
                    "vcrew-evt", "intro", LinkDomain.of("link-vcrew-evt-" + System.nanoTime()),
                    PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(8500L));
            Long id = partyroomRepository.saveAndFlush(p).getId();
            PartyroomId pid = new PartyroomId(id);
            entityManager.persist(PartyroomPlaybackData.createFor(pid));
            entityManager.persist(DjQueueData.createFor(pid));

            Long songPackId = seedSongPack();
            configRepository.save(PartyroomVirtualCrewConfigData.builder()
                    .partyroomId(id).status(VirtualCrewStatus.MANAGED)
                    .targetCount(2).djBotCount(2).songPackId(songPackId).build());
            this.roomId = id;
        });

        // 풀 충분(≥2 idle bots).
        poolService.provision(3);
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContext.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM dj").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM crew").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom_playback").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dj_queue").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom_virtual_crew_config").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom").executeUpdate();
        });
    }

    @Test
    @DisplayName("PartyroomTerminatedEvent(AFTER_COMMIT) → config OFF 전환, reconcile 안 함")
    void terminated_turns_config_off_and_skips_reconcile() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomTerminatedEvent(
                        new PartyroomId(roomId), adminId, "test-terminate")));

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    PartyroomVirtualCrewConfigData cfg = configRepository.findByPartyroomId(roomId).orElseThrow();
                    assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.OFF);
                });
        // 종료 룸은 reconcile 하지 않으므로 봇이 추가되지 않는다.
        assertThat(activeBotDjCount(roomId)).isZero();
    }

    // ── helpers ──

    private Long seedSongPack() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Pack", "IT"));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "vidA", "Song A", "2:00", null));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, "vidB", "Song B", "3:00", null));
        return pack.getId();
    }

    private long activeBotDjCount(long rid) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM dj d " +
                        "JOIN crew c ON c.crew_id = d.crew_id " +
                        "JOIN user_account u ON u.user_id = c.user_id " +
                        "WHERE d.partyroom_id = :rid AND c.is_active = 1 AND u.is_dummy = 1")
                .setParameter("rid", rid)
                .getSingleResult();
        return n.longValue();
    }
}
