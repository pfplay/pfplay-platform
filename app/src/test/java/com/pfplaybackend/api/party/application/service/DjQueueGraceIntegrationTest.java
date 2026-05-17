package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.realtime.port.PresencePort;
import com.pfplaybackend.realtime.port.SessionRegistryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * DJ큐 라이프사이클 × presence-grace 통합 특성화(characterization) 회귀 잠금 (#225 / #209).
 *
 * <p>PR-1 으로 DJ큐 제거는 구조적으로 grace-gated 가 되었다: {@code DjData} 는 오직
 * {@link PartyroomAccessCommandService#handleDjQueueOnLeave}(즉 {@code exitInternal}/{@code expel})
 * 에서만 삭제되고, disconnect 경로에서 {@code exitInternal} 에 도달하는 유일한 길은
 * grace 만료 시 {@link PartyroomPresenceService#forceOffline} 이다. {@code markPending}
 * (grace 시작)은 {@code DjData} 를 절대 건드리지 않는다.
 *
 * <p>이 테스트는 그 불변을 실제 빈(Testcontainers MySQL+Redis)으로 끝까지 구동해 잠근다 —
 * 향후 리팩터링이 "disconnect 가 즉시 DJ 를 떨군다"(#225) 로 조용히 회귀하지 못하게 한다.
 * 구동 seam 은 {@code ClusterAPresenceIntegrationTest} 와 동일:
 * {@code sessionRegistryPort.register} → {@code presencePort.onSessionDisconnected/Connected},
 * 그리고 grace 만료 시뮬레이션은 {@code presenceService.forceOffline}.
 *
 * <p>특성화 — PR-1 프로덕션은 이미 올바르므로 첫 작성 시 GREEN 이 정상(이것이 곧 잠금).
 * 프로덕션 변경 없음. {@link UserProfileQueryPort} 만 직교 게이트로 MockBean.
 */
@Transactional
class DjQueueGraceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SessionRegistryPort sessionRegistryPort;
    @Autowired
    private PresencePort presencePort;
    @Autowired
    private PartyroomPresenceService presenceService;
    @Autowired
    private PartyroomAggregatePort aggregatePort;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** tryEnter 의 프로필 보유 invariant 게이트만 무력화 — presence/DJ큐 흐름과 직교. */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedProfileGate() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        // registry/presence Redis 키는 tx 밖이므로 케이스 간 격리를 위해 비운다.
        // 주의: flushAll 은 단일 fork(integrationTest 직렬 실행) 전제 — 병렬 fork 시 키 간섭 가능.
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    // ───────────────────────── 그래프 셋업 헬퍼 ─────────────────────────

    private PartyroomId persistFullActivePartyroom(UserId hostId) {
        PartyroomData partyroom = PartyroomData.create(
                "DJ grace 파티룸", "DJ큐 grace 잠금용 파티룸",
                LinkDomain.of("dj-grace-" + hostId.getUid()),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);
        entityManager.persist(partyroom);
        entityManager.flush();
        PartyroomId partyroomId = new PartyroomId(partyroom.getId());

        entityManager.persist(PartyroomPlaybackData.createFor(partyroomId));
        entityManager.persist(DjQueueData.createFor(partyroomId));
        return partyroomId;
    }

    private CrewData persistActiveCrew(PartyroomId partyroomId, UserId userId) {
        CrewData crew = CrewData.create(partyroomId, userId, GradeType.CLUBBER, null);
        entityManager.persist(crew);
        flushAndClear();
        return crew;
    }

    /** crewId 로 DJ큐에 한 줄 등록 (orderNumber 명시). */
    private DjData persistDjRow(PartyroomId partyroomId, long crewId, long playlistId, int orderNumber) {
        DjData dj = DjData.create(partyroomId, new PlaylistId(playlistId), new CrewId(crewId), orderNumber);
        entityManager.persist(dj);
        flushAndClear();
        return dj;
    }

    /** 이 crew 를 현재 재생 중인 DJ 로 만든다 (playbackState 활성화 + current_dj_crew_id 지정). */
    private void makeCurrentDj(PartyroomId partyroomId, long crewId) {
        PartyroomPlaybackData playback = aggregatePort.findPlaybackState(partyroomId);
        playback.activate(new PlaybackId(7777L), new CrewId(crewId));
        aggregatePort.savePlaybackState(playback);
        flushAndClear();
    }

    /** 벌크 JPQL(markPending/clearPending/deactivateCrew) 우회 → flush+clear 후 재조회. */
    private CrewData refetchCrew(PartyroomId partyroomId, UserId userId) {
        flushAndClear();
        return aggregatePort.findCrew(partyroomId, userId).orElseThrow();
    }

    private Optional<DjData> refetchDj(PartyroomId partyroomId, long crewId) {
        flushAndClear();
        return aggregatePort.findDj(partyroomId, new CrewId(crewId));
    }

    private PartyroomPlaybackData refetchPlayback(PartyroomId partyroomId) {
        flushAndClear();
        return aggregatePort.findPlaybackState(partyroomId);
    }

    // ───────────────────────── 시나리오 ─────────────────────────

    @Test
    @DisplayName("1) markPending 은 DjData 를 제거하지 않는다 — DJ 인 사용자의 마지막 세션 DISCONNECT 후 pending SET 이어도 DJ 행은 orderNumber/playlist 불변으로 잔존")
    void markPendingDoesNotRemoveDjData() {
        // given — 사용자가 룸에 ACTIVE 이고 DJ큐에 등록된 상태
        UserId hostId = new UserId(2000L);
        UserId userId = new UserId(2001L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();
        persistDjRow(partyroomId, crewId, 4242L, 1);

        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isFalse();
        assertThat(refetchDj(partyroomId, crewId)).isPresent();

        // when — 단일 세션 등록 후 DISCONNECT (grace 시작)
        String sid = "sid-dj-markpending";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);

        // then — grace 시작(pending SET)했지만 DJ 행은 그대로 (큐 보존)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isPendingExit())
                .as("disconnect → grace 시작: pending_exit_at SET")
                .isTrue();
        assertThat(after.isActive()).isTrue();

        Optional<DjData> djAfter = refetchDj(partyroomId, crewId);
        assertThat(djAfter)
                .as("#225: markPending 은 DjData 를 제거하지 않는다 (grace 동안 DJ큐 무결)")
                .isPresent();
        assertThat(djAfter.get().getOrderNumber())
                .as("orderNumber 불변")
                .isEqualTo(1);
        assertThat(djAfter.get().getPlaylistId())
                .as("playlist 불변")
                .isEqualTo(new PlaylistId(4242L));
    }

    @Test
    @DisplayName("2) grace 내 재접속 → DJ 위치/순서 보존 — DISCONNECT(pending) 후 재접속 시 pending 해제 + DjData 동일 orderNumber 유지")
    void graceReconnectPreservesDjPosition() {
        // given — DJ 인 active 사용자가 DISCONNECT 로 pending SET
        UserId hostId = new UserId(2010L);
        UserId userId = new UserId(2011L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();
        persistDjRow(partyroomId, crewId, 5151L, 3);

        String sid1 = "sid-dj-reconnect-1";
        sessionRegistryPort.register(sid1, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid1);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isTrue();
        assertThat(refetchDj(partyroomId, crewId)).isPresent();

        // when — grace 내 재접속 (새 세션 등록 + onSessionConnected)
        String sid2 = "sid-dj-reconnect-2";
        sessionRegistryPort.register(sid2, String.valueOf(userId.getUid()));
        presencePort.onSessionConnected(sid2);

        // then — pending 해제 + DJ 행 동일 orderNumber (grace 사이클 동안 재배치/제거 없음)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isPendingExit())
                .as("재접속 → grace 취소")
                .isFalse();
        assertThat(after.isActive()).isTrue();

        Optional<DjData> djAfter = refetchDj(partyroomId, crewId);
        assertThat(djAfter)
                .as("#225: grace 사이클 동안 DJ 행은 제거되지 않는다")
                .isPresent();
        assertThat(djAfter.get().getOrderNumber())
                .as("재접속 후에도 동일 orderNumber 보존 (재배치 없음)")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("3) grace 만료 → DJ 제거 — DISCONNECT(pending) 후 forceOffline 시 crew is_active=false, exited_at SET, DjData 제거 (handleDjQueueOnLeave 실행)")
    void graceExpiryRemovesDj() {
        // given — 현재 재생 중인 DJ 인 active 사용자가 DISCONNECT 로 pending SET
        UserId hostId = new UserId(2020L);
        UserId userId = new UserId(2021L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();
        persistDjRow(partyroomId, crewId, 6262L, 1);
        makeCurrentDj(partyroomId, crewId);

        String sid = "sid-dj-expiry";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isTrue();
        assertThat(refetchDj(partyroomId, crewId))
                .as("grace 동안에는 현재 DJ 여도 DJ 행 잔존")
                .isPresent();

        // when — grace TTL 만료 시뮬레이션
        presenceService.forceOffline(partyroomId, new CrewId(crewId));

        // then — crew OFFLINE 확정 + DJ 행 제거 (#225 핵심 잠금: exitInternal→handleDjQueueOnLeave)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isActive())
                .as("grace 만료 → crew 비활성화")
                .isFalse();
        assertThat(after.getExitedAt())
                .as("grace 만료 → exited_at 기록")
                .isNotNull();
        assertThat(refetchDj(partyroomId, crewId))
                .as("#225: grace 만료 시에만 DJ 행 제거 (handleDjQueueOnLeave via exitInternal)")
                .isEmpty();
        // 현재 DJ 였으므로 skipPlayback 발화 — observable 효과(playbackState 비활성/재배치)는
        // playlist/스케줄러 인프라 광범위 셋업이 필요해 best-effort: 큐가 비었으므로
        // tryProceed → deactivate 경로로 playbackState 가 더 이상 이 crew 의 활성 상태가 아님만 확인.
        PartyroomPlaybackData playbackAfter = refetchPlayback(partyroomId);
        assertThat(playbackAfter.isCurrentDj(new CrewId(crewId)))
                .as("best-effort: 제거된 현재 DJ 가 더 이상 current 가 아니어야 함 (skip 발화 관찰)")
                .isFalse();
    }

    @Test
    @DisplayName("4) 현재 DJ 균일 grace (SE1) — 현재 재생 중인 DJ 의 DISCONNECT 는 DjData 를 제거하지 않고 skip 도 발화하지 않는다 (pending DJ 는 grace 동안 계속 재생)")
    void currentDjUniformGraceNoSkipOnDisconnect() {
        // given — 현재 재생 중인 DJ 인 active 사용자
        UserId hostId = new UserId(2030L);
        UserId userId = new UserId(2031L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();
        persistDjRow(partyroomId, crewId, 7373L, 1);
        makeCurrentDj(partyroomId, crewId);

        assertThat(refetchPlayback(partyroomId).isCurrentDj(new CrewId(crewId))).isTrue();

        // when — 현재 DJ DISCONNECT (마지막 세션)
        String sid = "sid-dj-current-disconnect";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);

        // then — pending SET 이지만 DJ 행 잔존 + skip 미발화 (현재 DJ 가 grace 동안 계속 재생)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isPendingExit())
                .as("현재 DJ 도 균일 grace: disconnect 시 pending SET")
                .isTrue();
        assertThat(after.isActive()).isTrue();

        assertThat(refetchDj(partyroomId, crewId))
                .as("SE1: markPending 은 현재 DJ 의 DjData 도 제거하지 않는다")
                .isPresent();

        PartyroomPlaybackData playbackAfter = refetchPlayback(partyroomId);
        assertThat(playbackAfter.isActivated())
                .as("SE1: disconnect 는 skipPlayback 을 발화하지 않는다 — playback 여전히 활성")
                .isTrue();
        assertThat(playbackAfter.isCurrentDj(new CrewId(crewId)))
                .as("SE1: pending DJ 는 grace 동안 계속 current — doStart 에 pending-DJ 체크 없음, skip 은 만료 시에만")
                .isTrue();
    }
}
