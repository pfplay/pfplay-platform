package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.administration.adapter.out.maintenance.ActiveMaintenanceGate;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 회귀 테스트 — 점검 게이트 캐시 staleness 로 인한 <b>점검 종료 후 자동부활 no-op</b> 재현·수정 검증.
 *
 * <p><b>버그:</b> vdj reconcile 스케줄러(60s)가 점검 중 {@link ActiveMaintenanceGate#isUnderMaintenance()}
 * 를 호출해 30s 스냅샷 캐시를 "true" 로 프라임한다. 운영자가 점검을 완료/취소하면
 * {@code MaintenanceEndedEvent} 의 AFTER_COMMIT 리스너({@code VirtualDjMaintenanceListener} →
 * {@code placeAllManagedIfActive})가 게이트를 확인하는데, 캐시가 stale "true" 라 최대 30s 동안
 * {@code SKIP_UNDER_MAINTENANCE} 로그만 남기고 <b>부활하지 않는다</b>. reconcile 안전망 스윕이 이번
 * 리팩토링에서 제거되어 재시도가 없으므로, 방은 수동 {@code /revive} 나 재부팅 전까지 드레인 상태로 남는다.
 *
 * <p><b>수정:</b> {@link ActiveMaintenanceGate} 가 점검 상태 전이 이벤트를 {@code @Order(HIGHEST_PRECEDENCE)}
 * AFTER_COMMIT 리스너로 받아 캐시를 무효화한다. 부활 리스너({@code @Order} 없음=LOWEST_PRECEDENCE)보다
 * <b>먼저</b> 실행되므로, 부활 리스너가 게이트를 재확인할 때는 캐시가 비워져 점검 종료 후의 DB 상태(=false)를
 * 신선하게 재조회 → 부활이 진행된다.
 *
 * <p><b>실제 게이트를 써야 하므로 {@code MaintenanceGate} 를 mock 하지 않는다</b>(캐시·evictor 경로를
 * 그대로 태워야 한다). 대신 실제 ACTIVE 점검 공지를 커밋 시드하고, 게이트를 stale "true" 로 프라임한 뒤,
 * 점검을 완료 커밋하고 {@code MaintenanceEndedEvent} 를 <b>커밋되는 트랜잭션</b>에서 publish 한다.
 *
 * <p><b>수정 전:</b> 프라임된 stale "true" 가 부활을 게이트 → 봇 0(타임아웃 실패).
 * <b>수정 후:</b> evictor 가 먼저 캐시를 비워 재조회 false → 봇 배치(2 DJ + 1 listener).
 *
 * <p>클래스는 {@code @Transactional} 이면 안 된다(AFTER_COMMIT 리스너는 롤백-only 트랜잭션에선 발화 안 함).
 * 시드/정리는 {@link TransactionTemplate} 로 명시 커밋한다.
 */
class VirtualDjMaintenanceGateEvictionIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualDjConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;
    @Autowired private SystemAnnouncementRepository announcementRepository;
    @Autowired private Clock clock;

    // 실제 게이트(캐시 + evictor)를 그대로 사용 — mock 하지 않는다. invalidate() 프라임을 위해 구체 타입 주입.
    @Autowired private ActiveMaintenanceGate maintenanceGate;

    @MockBean private UserProfileQueryPort userProfileQueryPort;

    private Long roomId;
    private Long announcementId;

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

        // 봇 프로비저닝이 쓰는 기본 아바타 바디 시드 (test 프로파일은 Flyway 비활성). 자체 커밋.
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    "https://example.test/icon_basic_001.png",
                    ObtainmentType.BASIC, 0, false, true, 60, 41, null);
            body.publish(null);
            avatarBodyResourceRepository.save(body);
        }

        // MANAGED 룸 + 송팩(≥2 재생가능 트랙)을 실제로 커밋. targetCount=3, djBotCount=2 → 2 DJ + 1 listener.
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = PartyroomData.create(
                    "vdj-gate", "intro", LinkDomain.of("link-vdj-gate-" + System.nanoTime()),
                    PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(8701L));
            Long id = partyroomRepository.saveAndFlush(p).getId();
            PartyroomId pid = new PartyroomId(id);
            entityManager.persist(PartyroomPlaybackData.createFor(pid));
            entityManager.persist(DjQueueData.createFor(pid));

            Long songPackId = seedSongPack();
            configRepository.save(PartyroomVirtualDjConfigData.builder()
                    .partyroomId(id).status(VirtualDjStatus.MANAGED)
                    .targetCount(3).djBotCount(2).songPackId(songPackId).build());
            this.roomId = id;
        });

        // 유휴 봇 프로비저닝(자체 @Transactional → 커밋). 2 DJ + 1 listener = 3 필요, 여유 4.
        poolService.provision(4);
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContext.clearContext();
        maintenanceGate.invalidate();
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM dj").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM crew").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom_playback").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dj_queue").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom_virtual_dj_config").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM system_announcement").executeUpdate();
        });
    }

    @Test
    @DisplayName("점검 중 게이트가 stale 'true' 로 프라임돼도, 점검 종료 이벤트가 캐시를 무효화해 봇이 부활한다(수정 전엔 0)")
    void maintenance_end_evicts_primed_gate_cache_and_revives() {
        // 1) 실제 ACTIVE 점검 공지 커밋 시드 → findCurrentMaintenance() 가 이것을 반환(점검 중).
        transactionTemplate.executeWithoutResult(status -> {
            SystemAnnouncementData a = SystemAnnouncementData.create(
                    AnnouncementType.MAINTENANCE_NOTICE, AnnouncementSeverity.WARN,
                    "점검", "Maint", "점검 중입니다", "Under maintenance",
                    LocalDateTime.now(clock).minusHours(1), LocalDateTime.now(clock).plusHours(1), null,
                    LocalDateTime.now(clock), 1L, false);
            a.markMaintenanceStarted(clock); // maintenanceStartedAt 설정 → ACTIVE 점검.
            this.announcementId = announcementRepository.saveAndFlush(a).getId();
        });

        // 2) 게이트를 stale "true" 로 프라임 — reconcile 스케줄러가 점검 중 게이트를 조회한 상황 재현.
        //    invalidate() 로 이전 캐시(부팅 부활 등)를 비운 뒤 신선 조회하면 announcement 로 true 가 캐시된다.
        maintenanceGate.invalidate();
        assertThat(maintenanceGate.isUnderMaintenance()).isTrue();

        // 룸은 아직 봇 0(드레인 상태). 사전조건 확인.
        assertThat(activeBotDjCount(roomId)).isZero();

        // 3) 점검 완료 커밋 → findCurrentMaintenance() 는 이제 empty(점검 아님). 단, 게이트 캐시는 여전히 stale "true".
        transactionTemplate.executeWithoutResult(status -> {
            SystemAnnouncementData a = announcementRepository.findById(announcementId).orElseThrow();
            a.markCompleted(clock);
            announcementRepository.saveAndFlush(a);
        });

        // 4) 커밋되는 트랜잭션에서 MaintenanceEndedEvent publish → AFTER_COMMIT 리스너 발화.
        //    evictor(HIGHEST_PRECEDENCE) 가 먼저 캐시 무효화 → 부활 리스너가 게이트 재조회 시 fresh false → 부활.
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new MaintenanceEndedEvent(mock(SystemAnnouncementData.class))));

        // 수정 없으면 stale "true" 로 SKIP → 영원히 0 → 타임아웃 실패.
        // 수정 있으면 캐시 무효화 → 재조회 false → 2 DJ + 1 listener 배치.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(activeBotDjCount(roomId)).isEqualTo(2);
                    assertThat(activeBotCrewCount(roomId)).isEqualTo(3);
                });
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

    private long activeBotCrewCount(long rid) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM crew c " +
                        "JOIN user_account u ON u.user_id = c.user_id " +
                        "WHERE c.partyroom_id = :rid AND c.is_active = 1 AND u.is_dummy = 1")
                .setParameter("rid", rid)
                .getSingleResult();
        return n.longValue();
    }
}
