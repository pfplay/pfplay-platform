package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
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
 * 회귀 테스트 — 점검 라이프사이클 이벤트({@code @TransactionalEventListener(AFTER_COMMIT)})가
 * 오케스트레이터 봇 드레인/부활 쓰기를 <b>실제로 커밋</b>하는지 검증한다.
 *
 * <p><b>왜 실제 이벤트 경로여야 하는가:</b> 이 버그는 {@code AFTER_COMMIT} phase 의 미묘함에서 온다.
 * 커밋을 트리거한 트랜잭션의 리소스가 커밋 <b>직후에도 스레드에 바인딩된 채 남아</b> 있어, 이 phase
 * 안에서 호출되는 {@code @Transactional(REQUIRED)} 는 <b>새 트랜잭션을 열지 않고 이미 완료된
 * 트랜잭션에 편승</b>한다 → 하위 쓰기가 "no transaction is in progress" /
 * "Executing an update/delete query" 로 실패하고, {@code BotPlacementService} 의 per-bot try/catch 가
 * 이를 WARN 으로 삼켜 <b>무음 no-op</b> 이 된다. 오케스트레이터를 tx-free 컨텍스트에서 <b>직접</b>
 * 부르는 테스트로는 이 편승이 재현되지 않는다(그 경우 REQUIRED 가 새 tx 를 연다) — 반드시 실제
 * {@code MaintenanceStarted/EndedEvent} 를 <b>커밋되는 트랜잭션 안에서</b> publish 해 AFTER_COMMIT
 * 리스너 → sweeper → orchestrator 경로를 그대로 타야 한다.
 *
 * <p>테스트 클래스는 {@code @Transactional} 이면 안 된다(AFTER_COMMIT 리스너는 롤백-only 트랜잭션에선
 * 발화하지 않음). 시드/정리는 {@link TransactionTemplate} 로 명시 커밋한다
 * ({@link VirtualCrewEventListenerIT} 와 동일 패턴).
 *
 * <p><b>수정 전:</b> 두 assertion 모두 봇 수가 그대로(부활=0, 드레인 후=2 잔존)로 실패한다.
 * <b>수정 후:</b> 오케스트레이터 {@code reconcileRoom/drainRoom} 의 {@code @Transactional} 이 이
 * phase 에서 별 트랜잭션을 확보(리소스 정리 후 새 tx)해 쓰기가 커밋되어 통과한다.
 */
class VirtualCrewOrchestratorTransactionalIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualCrewConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @MockBean private UserProfileQueryPort userProfileQueryPort;
    // placeAllManagedIfActive 의 점검 게이트 — 부활 테스트에서 "점검 중 아님" 으로 통과시킨다.
    @MockBean private MaintenanceGate maintenanceGate;

    private Long roomId;

    @BeforeEach
    void seed() {
        // 어떤 userId 든 프로필 보유 상태로 — placeToTarget 의 assertHasProfile 게이트 통과.
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        lenient().when(maintenanceGate.isUnderMaintenance()).thenReturn(false);

        // 봇 프로비저닝이 쓰는 기본 아바타 바디 시드 (test 프로파일은 Flyway 비활성). 자체 커밋.
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    "https://example.test/icon_basic_001.png",
                    ObtainmentType.BASIC, 0, false, true, 60, 41, null);
            body.publish(null);
            avatarBodyResourceRepository.save(body);
        }

        // MANAGED 룸 + 송팩(≥2 재생가능 트랙)을 실제로 커밋.
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = PartyroomData.create(
                    "vcrew-tx", "intro", LinkDomain.of("link-vcrew-tx-" + System.nanoTime()),
                    PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(8700L));
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

        // 유휴 봇 프로비저닝(자체 @Transactional → 커밋). ≥2 idle bots.
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
    @DisplayName("MaintenanceEndedEvent(AFTER_COMMIT) → 봇 DJ 2명이 실제로 커밋 배치된다(수정 전엔 0)")
    void maintenance_ended_after_commit_commits_bot_placement() {
        // 커밋되는 트랜잭션 안에서 publish → AFTER_COMMIT 리스너 발화(실제 점검종료 경로 재현).
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new MaintenanceEndedEvent(mock(SystemAnnouncementData.class))));

        // 커밋되지 않았다면 영원히 0 → 타임아웃 실패.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(activeBotDjCount(roomId)).isEqualTo(2));
    }

    @Test
    @DisplayName("MaintenanceStartedEvent(AFTER_COMMIT) → 봇 드레인 커밋, config 는 MANAGED 유지(수정 전엔 잔존)")
    void maintenance_started_after_commit_commits_bot_drain() {
        // 선행: 봇 2명 배치(부활 이벤트로).
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new MaintenanceEndedEvent(mock(SystemAnnouncementData.class))));
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(activeBotDjCount(roomId)).isEqualTo(2));

        // 점검 시작 → 드레인.
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new MaintenanceStartedEvent(mock(SystemAnnouncementData.class))));

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(activeBotDjCount(roomId)).isZero());
        // drain 은 봇만 제거하고 config 는 MANAGED 유지.
        PartyroomVirtualCrewConfigData cfg = configRepository.findByPartyroomId(roomId).orElseThrow();
        assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.MANAGED);
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
