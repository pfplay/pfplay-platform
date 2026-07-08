package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.CrewGradeChangedEvent;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.service.PartyroomAggregateService;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomAccessCommandServiceTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private PartyroomAggregateService partyroomAggregateService;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private PlaybackControlPort playbackControlPort;
    @Mock private UserProfileQueryPort userProfileQueryPort;
    @Mock private Clock clock;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private PartyroomAccessCommandService partyroomAccessCommandService;

    private UserId userId;
    private PartyroomId partyroomId;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        lenient().when(clock.millis()).thenReturn(1735689600000L);
        userId = new UserId();
        partyroomId = new PartyroomId(1L);

        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);

        // assertHasProfile 가드는 모든 tryEnter/enterByHost 진입에서 호출되므로
        // happy-path 테스트는 어떤 userId든 profile 보유 상태로 stub (다양한 테스트가 다른 userId를 사용).
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    java.util.List<UserId> ids = inv.getArgument(0);
                    java.util.Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });

        // @PostConstruct does not run with @InjectMocks — manually wire the TransactionTemplate
        // by calling initTxTemplates via reflection or leaving requiresNewReadOnlyTx null.
        // Since findCrewInNewTransaction is only called on DataIntegrityViolationException path
        // (not exercised in these unit tests), this is safe for the happy-path tests below.
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("같은 룸 재진입(websocket 재연결) 시 ENTER 이벤트를 발행하지 않아야 한다 (spec §7.2 spurious ENTER 차단)")
    void tryEnterSameRoomReEntryShouldNotPublishEnterEvent() {
        // given
        CrewData crew = CrewData.builder()
                .id(10L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData partyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(partyroomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(10L);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(crew);

        // 같은 룸에 이미 active
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(1L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // when
        var result = partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — spec §7.2: 같은 룸 재진입 시 ENTER 이벤트 발행 금지 (counter inflate 방지)
        verifyNoInteractions(eventPublisher);
        // 멤버십 유지(이미 active) → reactivated=false (#402)
        assertThat(result.reactivated()).isFalse();
    }

    @Test
    @DisplayName("다른 룸이 active일 때 ACTIVE_ANOTHER_ROOM 예외 대신 exit이 호출되어야 한다")
    void tryEnterAnotherRoomActiveShouldAutoExitInsteadOfException() {
        // given
        PartyroomId newRoomId = new PartyroomId(2L);
        PartyroomId oldRoomId = new PartyroomId(1L);

        // 새 룸 PartyroomData
        CrewData newRoomCrew = CrewData.builder()
                .id(20L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        PartyroomData newPartyroomData = PartyroomData.builder()
                .id(2L)
                .partyroomId(newRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(newRoomId)).thenReturn(newPartyroomData);
        when(aggregatePort.countActiveCrews(newRoomId)).thenReturn(5L);
        when(aggregatePort.findCrew(newRoomId, userId)).thenReturn(Optional.of(newRoomCrew));

        // 다른 룸에 이미 active
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(oldRoomId.getId());
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // exit() 호출 시 필요한 mock — 기존 룸 조회
        CrewData oldCrew = CrewData.builder()
                .id(5L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData oldPartyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(oldRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        PartyroomPlaybackData oldPlaybackState = PartyroomPlaybackData.createFor(new PartyroomId(1L));

        when(partyroomQueryService.getPartyroomById(oldRoomId)).thenReturn(oldPartyroomData);
        when(aggregatePort.findCrew(oldRoomId, userId)).thenReturn(Optional.of(oldCrew));
        when(aggregatePort.findDj(oldRoomId, new CrewId(5L))).thenReturn(Optional.empty());
        when(aggregatePort.findPlaybackState(oldRoomId)).thenReturn(oldPlaybackState);
        // deactivateCrew returns 1 → exit publishes EXIT event
        when(aggregatePort.deactivateCrew(eq(oldRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);

        // activateCrew returns 1 → ensureCrewActive transitions inactive→active → publishes ENTER
        when(aggregatePort.activateCrew(eq(newRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(newRoomCrew);

        // when — 예외 없이 정상 실행되어야 함
        var result = partyroomAccessCommandService.tryEnter(newRoomId, null);

        // then — EXIT event (from old room exit) + ENTER event (new room) = exactly 2 publish calls
        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
        // inactive→active 재활성(transitioned) → reactivated=true (#402)
        assertThat(result.reactivated()).isTrue();
    }

    @Test
    @DisplayName("exit — 이미 inactive인 crew에 대한 후속 DELETE는 예외 없이 no-op 반환 (Amplitude L2 mobile pagehide 멱등성)")
    void exitOnAlreadyInactiveCrewShouldBeIdempotentNoOp() {
        // given — crew row가 존재하지만 이미 isActive=false (이전 exit 호출로 deactivate됨)
        CrewData inactiveCrew = CrewData.builder()
                .id(42L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        PartyroomData partyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(partyroomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(inactiveCrew));
        // 멱등 toggle: 이미 inactive → UPDATE WHERE is_active=true는 0 row 영향
        when(aggregatePort.deactivateCrew(eq(partyroomId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(0);

        // when — 후속 DELETE /crews/me 호출 (mobile pagehide + visibilitychange 등 다중 fire 시나리오)
        partyroomAccessCommandService.exit(partyroomId);

        // then — 예외 없이 정상 반환 + EXIT event 미발행 + DJ queue 처리도 skip (이미 종료된 effect를 또 일으키면 안 됨)
        verifyNoInteractions(eventPublisher);
        verify(aggregatePort, never()).findDj(any(), any());
        verify(aggregatePort, never()).findPlaybackState(any());
        verify(partyroomAggregateService, never()).removeDjFromQueue(any(), any());
        verify(playbackControlPort, never()).skipPlayback(any());
    }

    @Test
    @DisplayName("exit — 첫 호출은 deactivate 1 → EXIT 이벤트 발행 (멱등성 분기와 대비되는 baseline)")
    void exitFirstCallShouldDeactivateAndPublishEvent() {
        // given — 정상 active crew
        CrewData activeCrew = CrewData.builder()
                .id(42L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData partyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(partyroomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        PartyroomPlaybackData playbackState = PartyroomPlaybackData.createFor(partyroomId);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(activeCrew));
        when(aggregatePort.deactivateCrew(eq(partyroomId), eq(userId), any(LocalDateTime.class)))
                .thenReturn(1);
        when(aggregatePort.findDj(eq(partyroomId), any(CrewId.class))).thenReturn(Optional.empty());
        when(aggregatePort.findPlaybackState(partyroomId)).thenReturn(playbackState);

        // when
        partyroomAccessCommandService.exit(partyroomId);

        // then — EXIT 이벤트 1회 발행
        verify(eventPublisher, times(1)).publishEvent(any(CrewAccessedEvent.class));
    }

    @Test
    @DisplayName("tryEnter INSERT race 패배자는 winner를 별 트랜잭션에서 조회하고 ENTER 이벤트를 발행하지 않는다")
    void tryEnterConcurrentInsertLoserShouldNotPublishEnter() {
        // given
        PartyroomId racePartyroomId = new PartyroomId(700L);
        UserId raceUserId = new UserId(8001L);
        AuthContext authContext = new AuthContext(raceUserId, AuthorityTier.GT);
        ThreadLocalContext.setContext(authContext);

        try {
            PartyroomData partyroom = mock(PartyroomData.class);
            when(partyroom.getPartyroomId()).thenReturn(racePartyroomId);
            when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
            when(partyroom.isSuspended()).thenReturn(false);
            when(partyroomQueryService.getPartyroomById(racePartyroomId)).thenReturn(partyroom);
            when(aggregatePort.countActiveCrews(racePartyroomId)).thenReturn(0L);
            when(partyroomQueryService.getMyActivePartyroom(raceUserId)).thenReturn(Optional.empty());

            // INSERT race scenario: activateCrew=0 (no existing row), findCrew initially empty,
            // saveCrew throws UNIQUE constraint violation simulating concurrent INSERT.
            // findCrew is called 3 times for this path:
            //   1. tryEnter line 69: existingCrew (for PartyroomEntrySpecification + same-room guard)
            //   2. ensureCrewActive line 138: existing (row missing check → triggers INSERT attempt)
            //   3. findCrewInNewTransaction (REQUIRES_NEW SELECT): winner row
            when(aggregatePort.activateCrew(eq(racePartyroomId), eq(raceUserId), any())).thenReturn(0);
            CrewData winnerCrew = mock(CrewData.class);
            when(aggregatePort.findCrew(eq(racePartyroomId), eq(raceUserId)))
                    .thenReturn(Optional.empty())               // call 1: tryEnter existingCrew check
                    .thenReturn(Optional.empty())               // call 2: ensureCrewActive row-missing check → INSERT
                    .thenReturn(Optional.of(winnerCrew));       // call 3: REQUIRES_NEW SELECT → winner
            when(aggregatePort.saveCrew(any())).thenThrow(new DataIntegrityViolationException("uk_crew_partyroom_user"));

            // Initialize requiresNewReadOnlyTx (skipped by @InjectMocks since @PostConstruct doesn't fire)
            TransactionStatus txStatus = mock(TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(txStatus);
            ReflectionTestUtils.invokeMethod(partyroomAccessCommandService, "initTxTemplates");

            // when
            CrewData result = partyroomAccessCommandService.tryEnter(racePartyroomId, CountryCode.of("KR")).crew();

            // then: returned crew is the winner row found in the REQUIRES_NEW transaction
            assertThat(result).isSameAs(winnerCrew);
            // No ENTER event published — concurrent insert loser must not inflate the counter
            verify(eventPublisher, never()).publishEvent(any(CrewAccessedEvent.class));
        } finally {
            ThreadLocalContext.clearContext();
        }
    }

    @Test
    @DisplayName("tryEnter: fresh entry, non-host user → CrewData created with LISTENER grade (regression)")
    void tryEnter_freshNonHostEntry_assignsListenerGrade() {
        // given — partyroom hosted by someone else, no existing crew row
        UserId hostId = new UserId(99L);
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        // host stub is lenient — Task 1 baseline: enforceHostInvariant helper not yet wired,
        // so the stub will only be consumed once Task 2 lands. Until then it is unused.
        lenient().when(partyroom.getHostId()).thenReturn(hostId);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(0);
        // CrewData.create()는 id를 부여하지 않으므로(JPA IDENTITY 컬럼) saveCrew stub이
        // ENTER 이벤트 발행 시 new CrewId(crew.getId())의 long unboxing NPE를 막기 위해
        // ReflectionTestUtils로 id를 시뮬레이션 주입.
        when(aggregatePort.saveCrew(any(CrewData.class)))
                .thenAnswer(inv -> {
                    CrewData input = inv.getArgument(0);
                    ReflectionTestUtils.setField(input, "id", 100L);
                    return input;
                });

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null).crew();

        // then
        assertThat(result.getGradeType()).isEqualTo(GradeType.LISTENER);
    }

    @Test
    @DisplayName("tryEnter: existing LISTENER row, non-host user → grade unchanged, updateGrade not invoked (regression)")
    void tryEnter_existingListenerNonHost_unchanged() {
        // given — partyroom hosted by someone else, user already a listener, currently inactive
        UserId hostId = new UserId(99L);
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        // host stub is lenient — see note in tryEnter_freshNonHostEntry_assignsListenerGrade.
        lenient().when(partyroom.getHostId()).thenReturn(hostId);

        CrewData existingCrew = spy(CrewData.builder()
                .id(20L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build());

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(existingCrew));
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null).crew();

        // then
        assertThat(result.getGradeType()).isEqualTo(GradeType.LISTENER);
        verify(existingCrew, never()).updateGrade(any(GradeType.class));
    }

    @Test
    @DisplayName("tryEnter: fresh entry, user is partyroom host → returned CrewData has HOST grade")
    void tryEnter_freshHostEntry_assignsHostGrade() {
        // given — partyroom hosted by THIS user, no existing crew row
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(userId);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(0);
        // C3 INSERT path: saveCrew must assign id (CrewId long-unbox NPE protection)
        when(aggregatePort.saveCrew(any(CrewData.class)))
                .thenAnswer(inv -> {
                    CrewData input = inv.getArgument(0);
                    ReflectionTestUtils.setField(input, "id", 100L);
                    return input;
                });

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null).crew();

        // then
        assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
    }

    @Test
    @DisplayName("tryEnter: existing LISTENER row, user is host, re-activate path → grade promoted to HOST")
    void tryEnter_existingListenerHost_promotesToHost() {
        // given — partyroom hosted by THIS user, existing inactive LISTENER row
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(userId);

        CrewData staleCrew = CrewData.builder()
                .id(30L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null).crew();

        // then
        assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
    }

    @Test
    @DisplayName("tryEnter: existing HOST row, user is host → updateGrade not invoked (helper short-circuit)")
    void tryEnter_existingHostHost_idempotent() {
        // given
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(userId);

        CrewData hostCrew = spy(CrewData.builder()
                .id(40L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(GradeType.HOST)
                .isActive(false)
                .build());

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(hostCrew));
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — helper must short-circuit when grade already HOST
        verify(hostCrew, never()).updateGrade(any(GradeType.class));
    }

    @Test
    @DisplayName("tryEnter: same-room re-entry, host with stale LISTENER row → grade promoted to HOST")
    void tryEnter_sameRoomReentry_promotesHostIfStale() {
        // given — partyroom hosted by THIS user, user already active in SAME room with stale LISTENER grade
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(userId);

        CrewData staleCrew = CrewData.builder()
                .id(50L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(1L);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
        when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

        // 같은 룸에 이미 active — same-room re-entry path
        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(1L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // when
        CrewData result = partyroomAccessCommandService.tryEnter(partyroomId, null).crew();

        // then
        assertThat(result.getGradeType()).isEqualTo(GradeType.HOST);
    }

    @Test
    @DisplayName("tryEnter: same-room 재입장 시 pending_exit_at을 해제한다 — SE4 레이스 이중방어 (#225)")
    void tryEnter_sameRoomReentry_clearsPendingExit() {
        // given — user already active in SAME room (page refresh: STOMP DISCONNECT set
        // pending_exit_at; reload triggers REST tryEnter same-room path)
        CrewData crew = CrewData.builder()
                .id(10L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData partyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(partyroomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroomData);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(10L);
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(crew));
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(crew);

        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(1L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — SE4 defense-in-depth: REST same-room re-entry cancels grace exactly once
        verify(aggregatePort, times(1)).clearCrewPending(partyroomId, userId);
    }

    @Test
    @DisplayName("tryEnter: 신규 진입(fresh)에서는 pending 해제를 호출하지 않는다 — same-room 분기 전용")
    void tryEnter_freshEntry_doesNotClearPending() {
        // given — no existing active room, fresh entry path
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.empty());
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(0);
        when(aggregatePort.saveCrew(any(CrewData.class)))
                .thenAnswer(inv -> {
                    CrewData input = inv.getArgument(0);
                    ReflectionTestUtils.setField(input, "id", 100L);
                    return input;
                });

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — pending clear belongs only to the same-room re-entry branch
        verify(aggregatePort, never()).clearCrewPending(any(), any());
    }

    @Test
    @DisplayName("tryEnter: 다른 룸에서 옮겨오는 신규 진입에서는 pending 해제를 호출하지 않는다 — same-room 분기 전용")
    void tryEnter_otherRoomAutoExit_doesNotClearPending() {
        // given — same scenario as tryEnterAnotherRoomActiveShouldAutoExitInsteadOfException
        PartyroomId newRoomId = new PartyroomId(2L);
        PartyroomId oldRoomId = new PartyroomId(1L);

        CrewData newRoomCrew = CrewData.builder()
                .id(20L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        PartyroomData newPartyroomData = PartyroomData.builder()
                .id(2L)
                .partyroomId(newRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        when(partyroomQueryService.getPartyroomById(newRoomId)).thenReturn(newPartyroomData);
        when(aggregatePort.countActiveCrews(newRoomId)).thenReturn(5L);
        when(aggregatePort.findCrew(newRoomId, userId)).thenReturn(Optional.of(newRoomCrew));

        ActivePartyroomDto activeRoomInfo = mock(ActivePartyroomDto.class);
        when(activeRoomInfo.id()).thenReturn(oldRoomId.getId());
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.of(activeRoomInfo));

        CrewData oldCrew = CrewData.builder()
                .id(5L)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(true)
                .build();

        PartyroomData oldPartyroomData = PartyroomData.builder()
                .id(1L)
                .partyroomId(oldRoomId)
                .status(PartyroomStatus.ACTIVE)
                .build();

        PartyroomPlaybackData oldPlaybackState = PartyroomPlaybackData.createFor(new PartyroomId(1L));

        when(partyroomQueryService.getPartyroomById(oldRoomId)).thenReturn(oldPartyroomData);
        when(aggregatePort.findCrew(oldRoomId, userId)).thenReturn(Optional.of(oldCrew));
        when(aggregatePort.findDj(oldRoomId, new CrewId(5L))).thenReturn(Optional.empty());
        when(aggregatePort.findPlaybackState(oldRoomId)).thenReturn(oldPlaybackState);
        when(aggregatePort.deactivateCrew(eq(oldRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);
        when(aggregatePort.activateCrew(eq(newRoomId), eq(userId), any(LocalDateTime.class))).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenReturn(newRoomCrew);

        // when
        partyroomAccessCommandService.tryEnter(newRoomId, null);

        // then — auto-exit-then-fresh-entry must NOT clear pending (not the same-room branch)
        verify(aggregatePort, never()).clearCrewPending(any(), any());
    }

    @Test
    @DisplayName("tryEnter healing: grade promotion is silent — no CrewGradeChangedEvent published")
    void tryEnter_healing_doesNotPublishGradeChangeEvent() {
        // given — same setup pattern as Test #3 (LISTENER → HOST healing path via re-activate)
        PartyroomData partyroom = mock(PartyroomData.class);
        when(partyroom.getPartyroomId()).thenReturn(partyroomId);
        when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
        when(partyroom.isSuspended()).thenReturn(false);
        when(partyroom.getHostId()).thenReturn(userId);

        CrewData staleCrew = CrewData.builder()
                .id(60L)
                .partyroomId(partyroomId)
                .userId(userId)
                .gradeType(GradeType.LISTENER)
                .isActive(false)
                .build();

        when(partyroomQueryService.getPartyroomById(partyroomId)).thenReturn(partyroom);
        when(aggregatePort.countActiveCrews(partyroomId)).thenReturn(0L);
        when(partyroomQueryService.getMyActivePartyroom(userId)).thenReturn(Optional.empty());
        when(aggregatePort.findCrew(partyroomId, userId)).thenReturn(Optional.of(staleCrew));
        when(aggregatePort.activateCrew(eq(partyroomId), eq(userId), any())).thenReturn(1);
        when(aggregatePort.saveCrew(any(CrewData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        partyroomAccessCommandService.tryEnter(partyroomId, null);

        // then — silent healing: no CrewGradeChangedEvent
        verify(eventPublisher, never()).publishEvent(any(CrewGradeChangedEvent.class));
    }

    @Test
    @DisplayName("tryEnter race-loser: helper skipped to avoid write on rollback-only outer tx")
    void tryEnter_raceLoser_skipsHealingToAvoidRollbackOnlyTx() {
        // given — same setup as tryEnterConcurrentInsertLoserShouldNotPublishEnter, but
        // user IS the host and winner row is LISTENER. The healing helper would normally
        // try to promote → saveCrew on rollback-only outer tx → UnexpectedRollbackException.
        // The raceLoser guard must skip the helper entirely.
        PartyroomId racePartyroomId = new PartyroomId(701L);
        UserId raceUserId = new UserId(8002L);
        AuthContext authContext = new AuthContext(raceUserId, AuthorityTier.GT);
        ThreadLocalContext.setContext(authContext);

        try {
            PartyroomData partyroom = mock(PartyroomData.class);
            when(partyroom.getPartyroomId()).thenReturn(racePartyroomId);
            when(partyroom.getStatus()).thenReturn(PartyroomStatus.ACTIVE);
            when(partyroom.isSuspended()).thenReturn(false);
            // lenient: getHostId() would be consumed by enforceHostInvariant if the raceLoser guard
            // were absent. With the guard applied the helper is skipped entirely → stub unused.
            lenient().when(partyroom.getHostId()).thenReturn(raceUserId);
            when(partyroomQueryService.getPartyroomById(racePartyroomId)).thenReturn(partyroom);
            when(aggregatePort.countActiveCrews(racePartyroomId)).thenReturn(0L);
            when(partyroomQueryService.getMyActivePartyroom(raceUserId)).thenReturn(Optional.empty());

            when(aggregatePort.activateCrew(eq(racePartyroomId), eq(raceUserId), any())).thenReturn(0);

            // Winner row from REQUIRES_NEW: a LISTENER row that WOULD trigger healing
            // if the guard were missing.
            CrewData winnerCrew = mock(CrewData.class);
            // lenient: getGradeType() would be consumed by enforceHostInvariant if guard were absent;
            // after guard is applied the helper is skipped so the stub goes unused — strict mode throws.
            lenient().when(winnerCrew.getGradeType()).thenReturn(GradeType.LISTENER);
            when(aggregatePort.findCrew(eq(racePartyroomId), eq(raceUserId)))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winnerCrew));
            // saveCrew는 두 경로 모두 trigger:
            //   1. ensureCrewActive INSERT 시도 → DataIntegrityViolationException → race-loser 분기 진입
            //   2. (가드 없을 시) enforceHostInvariant healing → 또 한 번 saveCrew → 동일 예외 → 테스트 RED
            // 가드 적용 후엔 helper skip되어 두 번째 호출 자체가 발생하지 않음.
            when(aggregatePort.saveCrew(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_crew_partyroom_user"));

            // Wire requiresNewReadOnlyTx (skipped by @InjectMocks since @PostConstruct doesn't fire)
            TransactionStatus txStatus = mock(TransactionStatus.class);
            when(transactionManager.getTransaction(any())).thenReturn(txStatus);
            ReflectionTestUtils.invokeMethod(partyroomAccessCommandService, "initTxTemplates");

            // when
            CrewData result = partyroomAccessCommandService.tryEnter(racePartyroomId, null).crew();

            // then — guard prevents the helper from invoking updateGrade or extra saveCrew
            assertThat(result).isSameAs(winnerCrew);
            verify(winnerCrew, never()).updateGrade(any(GradeType.class));
        } finally {
            ThreadLocalContext.clearContext();
        }
    }
}
