package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
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
import static org.mockito.Mockito.when;

/**
 * Cluster A PR-1 특성화(characterization) 회귀 잠금.
 *
 * <p>전반부 3건 — presence 경로는 사용자의 권위 있는(authoritative) active 룸을 STOMP subscribe
 * 타이밍이 아니라 기존 {@link PartyroomQueryPort#getActivePartyroomByUserId(UserId)} 계약으로
 * resolve 한다. 신규 포트 메서드를 추가하지 않고 이 기존 계약을 잠가, 후속 presence 리팩터링이
 * 룸-resolve 진실 원천을 조용히 깨뜨리지 못하게 한다.
 *
 * <p>후반부 시나리오 — PR-1(Task 1.1~1.7)으로 통합된 presence 흐름을 실제 Redis(user-session
 * registry) + MySQL(crew.pending_exit_at 권위) 로 끝까지 구동해 #31 / SE4(경로 A·B) / 멀티탭(E1) /
 * grace 만료 5개 행위를 잠근다. 구동 시seam 은 리스너가 호출하는 그대로:
 * {@code sessionRegistryPort.register(sid, uid)} → {@code presencePort.onSessionConnected(sid)}
 * (CONNECT), {@code presencePort.onSessionDisconnected(sid)} (DISCONNECT).
 *
 * <p>{@link UserProfileQueryPort} 만 MockBean — tryEnter 의 프로필 보유 invariant 가드는 presence
 * 행위와 직교하는 주변 게이트이므로(실제 ProfileData/AvatarSetting 행 구성은 잠금 대상이 아님)
 * 통과시킨다. presence DB 전이(pending_exit_at / is_active / exited_at)는 전부 실제 빈으로 구동·검증.
 *
 * <p>프로덕션 코드 변경 없음 — 작성·배선만으로 GREEN 인 것이 정상(이것이 곧 잠금).
 */
@Transactional
class ClusterAPresenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PartyroomQueryPort partyroomQueryPort;
    @Autowired
    private SessionRegistryPort sessionRegistryPort;
    @Autowired
    private PresencePort presencePort;
    @Autowired
    private PartyroomPresenceService presenceService;
    @Autowired
    private PartyroomAccessCommandService partyroomAccessCommandService;
    @Autowired
    private PartyroomAggregatePort aggregatePort;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * tryEnter 의 프로필 보유 invariant 게이트만 무력화 — presence 흐름과 직교.
     * (실제 ProfileData 행 구성은 PR-1 잠금 대상이 아니며 fragile.)
     */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedProfileGate() {
        // 어떤 userId 든 프로필 보유 상태로 — assertHasProfile 통과.
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

    private PartyroomId persistFullActivePartyroom(UserId hostId) {
        // 1) PartyroomData (root) — IDENTITY 생성 PK 확보
        PartyroomData partyroom = PartyroomData.create(
                "Cluster A 파티룸", "presence resolve 잠금용 파티룸",
                LinkDomain.of("cluster-a-presence-" + hostId.getUid()),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);
        entityManager.persist(partyroom);
        entityManager.flush();
        PartyroomId partyroomId = new PartyroomId(partyroom.getId());

        // 2) getActivePartyroomByUserId QueryDSL 은 PARTYROOM_PLAYBACK / DJ_QUEUE 와
        //    inner join 하므로 두 상태 행이 반드시 존재해야 한다.
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

    /** 벌크 JPQL(markPending/clearPending) 은 영속성 컨텍스트를 우회 → flush+clear 후 재조회. */
    private CrewData refetchCrew(PartyroomId partyroomId, UserId userId) {
        flushAndClear();
        return aggregatePort.findCrew(partyroomId, userId).orElseThrow();
    }

    private void setAuthContext(UserId userId) {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    // ───────────────────────── 기존 특성화 3건 (유지) ─────────────────────────

    @Test
    @DisplayName("getActivePartyroomByUserId — active crew 사용자는 그 파티룸의 ActivePartyroomDto 를 반환하고 dto.id() 는 PartyroomId 로 매핑된다")
    void activeCrewUserResolvesActivePartyroom() {
        // given
        UserId hostId = new UserId(900L);
        UserId crewUserId = new UserId(901L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);

        CrewData activeCrew = CrewData.create(
                partyroomId, crewUserId, GradeType.CLUBBER, null);
        entityManager.persist(activeCrew);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(crewUserId);

        // then
        assertThat(result).isPresent();
        ActivePartyroomDto dto = result.get();
        assertThat(dto.id()).isEqualTo(partyroomId.getId());
        // 후속 presence resolve 가 사용하는 DTO → 도메인 값 매핑이 유효함을 잠근다.
        assertThat(new PartyroomId(dto.id())).isEqualTo(partyroomId);
        assertThat(dto.crewId()).isEqualTo(activeCrew.getId());
    }

    @Test
    @DisplayName("getActivePartyroomByUserId — 비active crew 사용자는 Optional.empty() 를 반환한다")
    void inactiveCrewUserResolvesEmpty() {
        // given
        UserId hostId = new UserId(910L);
        UserId crewUserId = new UserId(911L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);

        CrewData inactiveCrew = CrewData.create(
                partyroomId, crewUserId, GradeType.CLUBBER, null);
        inactiveCrew.deactivatePresence();
        entityManager.persist(inactiveCrew);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(crewUserId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getActivePartyroomByUserId — crew 행이 없는 사용자는 Optional.empty() 를 반환한다")
    void absentCrewUserResolvesEmpty() {
        // given
        UserId hostId = new UserId(920L);
        UserId unknownUserId = new UserId(921L);
        persistFullActivePartyroom(hostId);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(unknownUserId);

        // then
        assertThat(result).isEmpty();
    }

    // ─────────────── PR-1 통합 시나리오 잠금 (#31 / SE4 / 멀티탭 / grace) ───────────────

    @Test
    @DisplayName("#31 — subscribe 여부 무관: 마지막 세션 DISCONNECT 가 항상 pending_exit_at 을 SET 한다 (registry 권위, subscribe-타이밍 캐시 비의존)")
    void disconnectAlwaysMarksPendingViaRegistry_31() {
        // given — 사용자가 룸에 ACTIVE (crew is_active=true, pending_exit_at NULL)
        UserId hostId = new UserId(1000L);
        UserId userId = new UserId(1001L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isFalse();

        // when — CONNECT 시 세션 등록(STOMP SUBSCRIBE 는 한 번도 없음) → 그 단일 세션 DISCONNECT
        // String uid 인자는 realtime CONNECT-frame Principal 계약(리스너가 넘기는 형태)을 그대로 미러링.
        String sid = "sid-31-only";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);

        // then — subscribe 캐시와 무관하게 grace 시작: pending_exit_at SET
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isPendingExit())
                .as("#31: 탭 종료/DISCONNECT 는 subscribe 캐시 게이팅 없이 항상 registry 경로로 pending 마크")
                .isTrue();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getExitedAt()).isNull();
    }

    @Test
    @DisplayName("SE4 경로 A — DISCONNECT(pending) 후 동일 유저 새 세션 CONNECT 시 pending_exit_at 이 해제된다")
    void se4PathA_connectTimeClearsPending() {
        // given — active → 세션 등록 → DISCONNECT 로 pending SET
        UserId hostId = new UserId(1010L);
        UserId userId = new UserId(1011L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        persistActiveCrew(partyroomId, userId);

        String sid1 = "sid-se4a-1";
        sessionRegistryPort.register(sid1, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid1);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isTrue();

        // when — 재접속: 새 세션을 CONNECT 시점에 등록 + onSessionConnected
        String sid2 = "sid-se4a-2";
        sessionRegistryPort.register(sid2, String.valueOf(userId.getUid()));
        presencePort.onSessionConnected(sid2);

        // then — pending 해제 (ONLINE 복원)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isPendingExit())
                .as("SE4 경로 A: CONNECT-time clearPending 으로 grace 취소")
                .isFalse();
        assertThat(after.isActive()).isTrue();
    }

    @Test
    @DisplayName("SE4 경로 B (end-to-end) — DISCONNECT(pending) 후 REST 같은-룸 재입장으로 pending 해제 + 늦게 만료된 stale 키의 forceOffline 은 NO-OP")
    void se4PathB_restReentryClears_thenLateExpiryIsNoOp() {
        // given — active → 세션 등록 → DISCONNECT 로 pending SET
        UserId hostId = new UserId(1020L);
        UserId userId = new UserId(1021L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();

        String sid = "sid-se4b";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isTrue();

        // when (1) — REST 같은-룸 재입장 (페이지 새로고침 흐름)
        setAuthContext(userId);
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then (1) — DB pending_exit_at 해제
        CrewData afterReentry = refetchCrew(partyroomId, userId);
        assertThat(afterReentry.isPendingExit())
                .as("SE4 경로 B: REST 같은-룸 재입장이 grace 를 이중방어로 취소")
                .isFalse();
        assertThat(afterReentry.isActive()).isTrue();

        // when (2) — 늦게 도착한 expiration listener 가 stale Redis 타이머 키로 forceOffline 발화
        presenceService.forceOffline(partyroomId, new CrewId(crewId));

        // then (2) — !isPendingExit() 가드로 NO-OP: crew 는 여전히 active, exited_at NULL, OFFLINE 아님
        CrewData afterLateExpiry = refetchCrew(partyroomId, userId);
        assertThat(afterLateExpiry.isActive())
                .as("T1.7 forward note: 재입장으로 pending 해제된 crew 는 늦은 만료에도 OFFLINE 되지 않아야 함")
                .isTrue();
        assertThat(afterLateExpiry.getExitedAt()).isNull();
        assertThat(afterLateExpiry.isPendingExit()).isFalse();
    }

    @Test
    @DisplayName("E1 멀티탭 — 두 세션 중 첫 DISCONNECT 는 pending 미발생, 마지막 DISCONNECT 에서만 pending SET (SE5 self-eviction 방지)")
    void e1MultiTab_onlyLastSessionStartsGrace() {
        // given — active, 같은 유저 두 세션 등록
        UserId hostId = new UserId(1030L);
        UserId userId = new UserId(1031L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        persistActiveCrew(partyroomId, userId);

        String s1 = "sid-e1-tab1";
        String s2 = "sid-e1-tab2";
        sessionRegistryPort.register(s1, String.valueOf(userId.getUid()));
        sessionRegistryPort.register(s2, String.valueOf(userId.getUid()));

        // when (1) — s1 만 DISCONNECT (마지막 세션 아님)
        presencePort.onSessionDisconnected(s1);

        // then (1) — pending 미발생 (다른 탭이 살아있음)
        assertThat(refetchCrew(partyroomId, userId).isPendingExit())
                .as("E1: 한 탭만 닫혔으므로 grace 타이머가 시작되면 안 됨 (SE5)")
                .isFalse();

        // when (2) — s2 DISCONNECT (마지막 세션)
        presencePort.onSessionDisconnected(s2);

        // then (2) — pending SET
        assertThat(refetchCrew(partyroomId, userId).isPendingExit())
                .as("E1: 마지막 세션 종료 시에만 grace 시작")
                .isTrue();
    }

    @Test
    @DisplayName("#31 grace 만료 → exited — DISCONNECT(pending) 후 forceOffline(grace TTL 만료) 시 crew is_active=false, exited_at SET (고스트 정리)")
    void graceExpiryPromotesToOffline_31EndState() {
        // given — active → 세션 등록 → DISCONNECT 로 pending SET
        UserId hostId = new UserId(1040L);
        UserId userId = new UserId(1041L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();

        String sid = "sid-31-expiry";
        sessionRegistryPort.register(sid, String.valueOf(userId.getUid()));
        presencePort.onSessionDisconnected(sid);
        assertThat(refetchCrew(partyroomId, userId).isPendingExit()).isTrue();

        // when — grace TTL 만료 시뮬레이션
        presenceService.forceOffline(partyroomId, new CrewId(crewId));

        // then — 고스트 최종 정리: is_active=false, exited_at SET (#31 종착 상태)
        CrewData after = refetchCrew(partyroomId, userId);
        assertThat(after.isActive())
                .as("#31 end state: grace 만료 시 고스트 crew 가 비활성화되어야 함")
                .isFalse();
        assertThat(after.getExitedAt())
                .as("#31 end state: exited_at 이 기록되어야 함")
                .isNotNull();
    }
}
