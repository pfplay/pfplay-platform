package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.application.dto.command.CreatePartyroomCommand;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
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
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
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
 * B/T1-3 (#212) — createGeneralPartyRoom → enterByHost 경로의 one-active-room invariant 강제.
 *
 * <p>버그: enterByHost 는 HOST CrewData 를 무조건 INSERT(is_active=true) — 다른 룸에 이미 active
 * 인 사용자가 신규 일반 파티룸을 만들면 두 룸에서 동시 active 가 된다. 부수효과로
 * getActivePartyroomByUserId 가 .fetchOne() → IncorrectResultSizeDataAccessException →
 * 해당 사용자는 어떤 룸도 입/퇴장 불가(wedge).
 *
 * <p>수정: createGeneralPartyRoom → enterByHost 경로가 tryEnter 와 동일한 active-room 체크 +
 * auto-exit 를 HOST crew INSERT 전에 수행 (DJ큐 정리 + playback skip 포함).
 *
 * <p>{@link UserProfileQueryPort} 만 MockBean — assertHasProfile 게이트는 본 invariant 와 직교.
 */
@Transactional
class CreateGeneralPartyRoomInvariantIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PartyroomCommandService partyroomCommandService;
    @Autowired
    private PartyroomQueryService partyroomQueryService;
    @Autowired
    private PartyroomAggregatePort aggregatePort;
    @Autowired
    private PartyroomPresenceService presenceService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        // presenceService.markPending 은 grace 시작 시 Redis 타이머 키를 쓴다 (tx 밖) —
        // 케이스 간 격리를 위해 비운다. flushAll 은 integrationTest 직렬 실행 전제.
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    private PartyroomId persistFullActivePartyroom(UserId hostId, String linkSuffix) {
        PartyroomData partyroom = PartyroomData.create(
                "기존 파티룸 A", "one-active-room invariant 잠금용",
                LinkDomain.of("b-t1-3-" + linkSuffix),
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

    private DjData persistDjRow(PartyroomId partyroomId, long crewId, long playlistId, int orderNumber) {
        DjData dj = DjData.create(partyroomId, new PlaylistId(playlistId), new CrewId(crewId), orderNumber);
        entityManager.persist(dj);
        flushAndClear();
        return dj;
    }

    private CrewData refetchCrew(PartyroomId partyroomId, UserId userId) {
        flushAndClear();
        return aggregatePort.findCrew(partyroomId, userId).orElseThrow();
    }

    private void setAuthContext(UserId userId) {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @Test
    @DisplayName("기존 룸 A 에 active + DJ 인 사용자가 createGeneralPartyRoom 시 — A 에서 auto-exit(crew is_active=false, DjData 제거) + 신규 룸에만 active + getActivePartyroom 단일(wedge 없음)")
    void createGeneralPartyRoomAutoExitsPriorActiveRoom() {
        // given — 사용자(userId)가 A 룸(다른 사람이 host)에 active CLUBBER + DJ큐 등록
        UserId roomAHostId = new UserId(3000L);
        UserId userId = new UserId(3001L);
        PartyroomId roomA = persistFullActivePartyroom(roomAHostId, "roomA-3001");
        CrewData crewInA = persistActiveCrew(roomA, userId);
        long crewIdInA = crewInA.getId();
        persistDjRow(roomA, crewIdInA, 9001L, 1);

        assertThat(refetchCrew(roomA, userId).isActive()).isTrue();
        assertThat(aggregatePort.findDj(roomA, new CrewId(crewIdInA))).isPresent();

        // when — userId 가 신규 일반 파티룸을 생성 (createGeneralPartyRoom → enterByHost)
        setAuthContext(userId);
        PartyroomData newRoom = partyroomCommandService.createGeneralPartyRoom(
                new CreatePartyroomCommand("내 새 파티룸", "신규", "b-t1-3-new-3001", 5));
        PartyroomId newRoomId = newRoom.getPartyroomId();

        // then (a) — A 의 crew 는 auto-exit (is_active=false)
        CrewData crewAfterA = refetchCrew(roomA, userId);
        assertThat(crewAfterA.isActive())
                .as("B/T1-3: 기존 룸 A 의 crew 는 신규 룸 생성 시 auto-exit 되어야 함")
                .isFalse();

        // then (b) — A 의 DJ 행 제거 (handleDjQueueOnLeave 실행)
        assertThat(aggregatePort.findDj(roomA, new CrewId(crewIdInA)))
                .as("B/T1-3: auto-exit 시 A 의 DjData 가 제거되어야 함 (DJ큐 정리)")
                .isEmpty();

        // then (c) — 신규 룸에만 active (HOST)
        CrewData crewInNew = refetchCrew(newRoomId, userId);
        assertThat(crewInNew.isActive())
                .as("신규 룸에서는 HOST 로 active")
                .isTrue();
        assertThat(crewInNew.getGradeType()).isEqualTo(GradeType.HOST);

        // then (d) — getActivePartyroomByUserId 가 신규 룸 단일 반환 (wedge 없음)
        Optional<ActivePartyroomDto> active = partyroomQueryService.getMyActivePartyroom(userId);
        assertThat(active)
                .as("B/T1-3: 단일 active 룸만 — IncorrectResultSizeDataAccessException(wedge) 없음")
                .isPresent();
        assertThat(active.get().id())
                .as("active 룸은 신규 룸이어야 함")
                .isEqualTo(newRoomId.getId());
    }

    @Test
    @DisplayName("기존 active 룸이 없는 사용자가 createGeneralPartyRoom 시 — 회귀 없음: 신규 룸에 HOST active, exit 된 것 없음")
    void createGeneralPartyRoomNoPriorRoomWorksAsBefore() {
        // given — 사용자에게 어떤 active 룸도 없음
        UserId userId = new UserId(3011L);
        setAuthContext(userId);

        // when
        PartyroomData newRoom = partyroomCommandService.createGeneralPartyRoom(
                new CreatePartyroomCommand("첫 파티룸", "신규", "b-t1-3-fresh-3011", 5));
        PartyroomId newRoomId = newRoom.getPartyroomId();

        // then — 신규 룸 HOST active, getActivePartyroom 단일
        CrewData crewInNew = refetchCrew(newRoomId, userId);
        assertThat(crewInNew.isActive()).isTrue();
        assertThat(crewInNew.getGradeType()).isEqualTo(GradeType.HOST);

        Optional<ActivePartyroomDto> active = partyroomQueryService.getMyActivePartyroom(userId);
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(newRoomId.getId());
    }

    @Test
    @DisplayName("기존 룸 A 에 active(non-host) + PENDING_EXIT(V16 grace) 인 사용자가 createGeneralPartyRoom 시 — grace 상태여도 is_active=true 라 A 가 auto-exit(crew is_active=false, DjData 제거) + 신규 룸 B 만 HOST active + getActivePartyroom 단일(grace wedge 없음)")
    void createGeneralPartyRoomAutoExitsPriorRoomEvenWhileInPendingExitGrace() {
        // given — userId 가 A 룸(다른 사람이 host)에 active CLUBBER + DJ큐 등록 상태.
        // 주의: createGeneralPartyRoom 은 enterByHost(B/T1-3 auto-exit) 도달 전에
        // findNonTerminatedHostRoom 가드(ALREADY_HOST)를 먼저 친다 — 따라서 prior 룸에서
        // 사용자는 non-host 여야 enterByHost 경로(=본 invariant)가 실제로 실행된다.
        // (사용자가 prior 룸 host 인 경우는 기존 ALREADY_HOST 가드가 별도로 차단 — 본 helper 직교)
        UserId roomAHostId = new UserId(3020L);
        UserId userId = new UserId(3021L);
        PartyroomId roomA = persistFullActivePartyroom(roomAHostId, "roomA-grace-3021");
        CrewData crewInA = persistActiveCrew(roomA, userId);
        long crewIdInA = crewInA.getId();
        persistDjRow(roomA, crewIdInA, 9021L, 1);

        // and — A 룸을 실제 프로덕션 grace 진입점(PartyroomPresenceService.markPending,
        // ONLINE → PENDING_EXIT)으로 grace 상태에 둔다. markPending 은 pending_exit_at 만
        // SET 하고 is_active=true 를 유지한다 (손수 SQL 금지 — 프로덕션 경로 그대로).
        presenceService.markPending(roomA, userId);
        CrewData crewInGrace = refetchCrew(roomA, userId);
        assertThat(crewInGrace.isPendingExit())
                .as("선행: A 룸은 V16 grace(PENDING_EXIT) 상태여야 함")
                .isTrue();
        assertThat(crewInGrace.isActive())
                .as("선행: PENDING_EXIT 는 is_active=true 를 유지 — getActivePartyroom 에 잡힘")
                .isTrue();
        assertThat(aggregatePort.findDj(roomA, new CrewId(crewIdInA))).isPresent();

        // when — userId 가 신규 일반 파티룸 B 를 생성 (createGeneralPartyRoom → enterByHost)
        setAuthContext(userId);
        PartyroomData newRoom = partyroomCommandService.createGeneralPartyRoom(
                new CreatePartyroomCommand("내 새 파티룸", "신규", "b-t1-3-grace-new-3021", 5));
        PartyroomId newRoomId = newRoom.getPartyroomId();

        // then (a) — grace 상태였던 A 의 crew 는 깔끔히 auto-exit (is_active=false) —
        // grace 에 끼인 채(wedge) 남지 않는다
        CrewData crewAfterA = refetchCrew(roomA, userId);
        assertThat(crewAfterA.isActive())
                .as("B/T1-3: PENDING_EXIT grace 상태의 A 도 신규 룸 생성 시 auto-exit (grace wedge 금지)")
                .isFalse();

        // then (b) — A 의 DJ 행 제거 (handleDjQueueOnLeave 실행)
        assertThat(aggregatePort.findDj(roomA, new CrewId(crewIdInA)))
                .as("B/T1-3: grace 상태에서 auto-exit 시에도 A 의 DjData 가 제거되어야 함 (DJ큐 정리)")
                .isEmpty();

        // then (c) — 신규 룸 B 에만 active (HOST)
        CrewData crewInNew = refetchCrew(newRoomId, userId);
        assertThat(crewInNew.isActive())
                .as("신규 룸 B 에서는 HOST 로 active")
                .isTrue();
        assertThat(crewInNew.getGradeType()).isEqualTo(GradeType.HOST);

        // then (d) — getActivePartyroomByUserId 가 신규 룸 B 단일 반환 (grace double-active wedge 없음)
        Optional<ActivePartyroomDto> active = partyroomQueryService.getMyActivePartyroom(userId);
        assertThat(active)
                .as("B/T1-3: 단일 active 룸만 — grace 잔존으로 인한 IncorrectResultSizeDataAccessException(wedge) 없음")
                .isPresent();
        assertThat(active.get().id())
                .as("active 룸은 신규 룸 B 여야 함")
                .isEqualTo(newRoomId.getId());
    }
}
