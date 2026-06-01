package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.application.service.DjCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.service.FlapGuard;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Chunk 5 이벤트 반응 통합 테스트 — 실 AFTER_COMMIT 경로로 listener → reconcile wiring 을 검증한다.
 *
 * <p><b>commit 경계 처리:</b> {@code @TransactionalEventListener(AFTER_COMMIT)} 는 커밋이 일어나야
 * 발화하므로 클래스-레벨 {@code @Transactional} 로는 트리거되지 않는다(롤백 only). 따라서 이 IT 는
 * 비-{@code @Transactional} 로 두고 {@link TransactionTemplate} 안에서 도메인 이벤트를 publish 하여
 * 실제 커밋 → listener 발화 경로를 그대로 탄다. 정리는 명시적으로 한다(UserActivityLogListener*IT 패턴).
 *
 * <p><b>무한루프/churn 가드 검증:</b> {@link DjCommandService} 를 {@link SpyBean} 으로 감싸
 * 재트리거 시 {@code enqueueDj} 가 추가로 호출되지 않음(멱등 수렴)을 {@code verify(..., times(n))} 로
 * 증명한다 — 최종 봇 수가 안정적이라는 것만이 아니라 churn 자체가 없음을 보인다.
 */
class VirtualDjEventListenerIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualDjConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @SpyBean private DjCommandService djCommandService;
    @SpyBean private FlapGuard flapGuard;

    @MockBean private UserProfileQueryPort userProfileQueryPort;

    private Long roomId;

    @BeforeEach
    void seed() {
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

        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = PartyroomData.create(
                    "vdj-evt", "intro", LinkDomain.of("link-vdj-evt-" + System.nanoTime()),
                    PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(8500L));
            Long id = partyroomRepository.saveAndFlush(p).getId();
            PartyroomId pid = new PartyroomId(id);
            entityManager.persist(PartyroomPlaybackData.createFor(pid));
            entityManager.persist(DjQueueData.createFor(pid));

            Long songPackId = seedSongPack();
            configRepository.save(PartyroomVirtualDjConfigData.builder()
                    .partyroomId(id).status(VirtualDjStatus.MANAGED)
                    .targetCount(2).companionFloor(1).songPackId(songPackId).build());
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
            entityManager.createNativeQuery("DELETE FROM partyroom_virtual_dj_config").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom").executeUpdate();
        });
    }

    @Test
    @DisplayName("CrewAccessedEvent(AFTER_COMMIT) → reconcile 봇 T(=2)까지 채움 + 재트리거는 churn 없음")
    void crewAccessed_triggers_reconcile_and_converges_without_churn() {
        // 1) 실제 커밋 후 listener 발화 → reconcile 봇 2명 투입.
        publishCrewEnterCommitted();

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(activeBotDjCount(roomId)).isEqualTo(2));

        // 봇 2명 투입 = enqueueDj 2회.
        verify(djCommandService, times(2)).enqueueDj(any(PartyroomId.class), any(PlaylistId.class));

        // 2) 재트리거 — 멱등 수렴 상태이므로 enqueueDj 가 한 번도 더 일어나면 안 된다(churn 없음).
        publishCrewEnterCommitted();

        // 약간 대기 후에도 카운트 불변 + enqueue 누적 불변.
        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(activeBotDjCount(roomId)).isEqualTo(2));
        verify(djCommandService, times(2)).enqueueDj(any(PartyroomId.class), any(PlaylistId.class));
    }

    @Test
    @DisplayName("PartyroomTerminatedEvent(AFTER_COMMIT) → config OFF 전환, reconcile 안 함")
    void terminated_turns_config_off_and_skips_reconcile() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomTerminatedEvent(
                        new PartyroomId(roomId), 9000L, "test-terminate")));

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    PartyroomVirtualDjConfigData cfg = configRepository.findByPartyroomId(roomId).orElseThrow();
                    assertThat(cfg.getStatus()).isEqualTo(VirtualDjStatus.OFF);
                });
        // 종료 룸은 reconcile 하지 않으므로 봇이 추가되지 않는다.
        assertThat(activeBotDjCount(roomId)).isZero();
        // 종료 경로가 FlapGuard removal-intent 를 정리한다.
        verify(flapGuard).clearRemovalIntent(new PartyroomId(roomId));
    }

    // ── helpers ──

    private void publishCrewEnterCommitted() {
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId), new CrewId(1L), new UserId(8500L), AccessType.ENTER)));
    }

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
