package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.DjCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor;
import com.pfplaybackend.api.virtualdj.application.service.ActiveDjSnapshotService;
import com.pfplaybackend.api.virtualdj.application.service.BotPlacementService;
import com.pfplaybackend.api.virtualdj.application.service.BotSlotAssigner;
import com.pfplaybackend.api.virtualdj.application.service.SongPackApplier;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BotPlacementService} 단위 테스트 — 고정 2역할 수렴이 사람 수와 무관함을 고정한다.
 */
class BotPlacementServiceTest {

    private PartyroomVirtualDjConfigRepository configRepository;
    private PartyroomQueryService partyroomQueryService;
    private ActiveDjSnapshotService activeDjSnapshotService;
    private BotPoolQueryRepository botPoolQueryRepository;
    private VirtualUserPoolService poolService;
    private SongPackApplier songPackApplier;
    private BotSlotAssigner botSlotAssigner;
    private BotIdentityExecutor botIdentity;
    private PartyroomAccessCommandService accessCommandService;
    private DjCommandService djCommandService;

    private BotPlacementService service;

    private static final PartyroomId ROOM = new PartyroomId(1L);
    private static final int TIME_LIMIT = 60;

    @BeforeEach
    void setUp() {
        configRepository = mock(PartyroomVirtualDjConfigRepository.class);
        partyroomQueryService = mock(PartyroomQueryService.class);
        activeDjSnapshotService = mock(ActiveDjSnapshotService.class);
        botPoolQueryRepository = mock(BotPoolQueryRepository.class);
        poolService = mock(VirtualUserPoolService.class);
        songPackApplier = mock(SongPackApplier.class);
        botSlotAssigner = mock(BotSlotAssigner.class);
        botIdentity = mock(BotIdentityExecutor.class);
        accessCommandService = mock(PartyroomAccessCommandService.class);
        djCommandService = mock(DjCommandService.class);

        service = new BotPlacementService(configRepository, partyroomQueryService, activeDjSnapshotService,
                botPoolQueryRepository, poolService, songPackApplier, botSlotAssigner, botIdentity,
                accessCommandService, djCommandService);

        // 룸 시간제한
        PartyroomData room = mock(PartyroomData.class);
        given(room.getPlaybackTimeLimit()).willReturn(PlaybackTimeLimit.ofMinutes(TIME_LIMIT));
        given(partyroomQueryService.getPartyroomById(ROOM)).willReturn(room);

        // 봇 신원 실행 = 넘긴 Runnable 을 그대로 실행(내부 tryEnter/enqueueDj/exit 검증 가능하게).
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(botIdentity).runAs(any(UserId.class), any(Runnable.class));

        // 봇 playlist id (enqueueDj lambda 내부)
        given(poolService.playlistIdOf(any(UserId.class))).willReturn(100L);
    }

    private void stubConfig(int targetCount, int djBotCount, Long songPackId) {
        PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.builder()
                .partyroomId(ROOM.getId())
                .status(VirtualDjStatus.MANAGED)
                .targetCount(targetCount)
                .djBotCount(djBotCount)
                .songPackId(songPackId)
                .build();
        given(configRepository.findByPartyroomId(ROOM.getId())).willReturn(Optional.of(cfg));
    }

    private void stubSnapshot(int humanCount, List<UserId> djBots) {
        given(activeDjSnapshotService.snapshot(ROOM))
                .willReturn(new ActiveDjSnapshotService.ActiveDjSnapshot(humanCount, djBots));
    }

    @Test
    @DisplayName("(a) DJ 부족 — 현재 DJ 1, djBotCount 3, 트랙 10 → 2봇 추가(slot 배정+chunk+입장+DJ등록)")
    void djShortfall() {
        stubConfig(3, 3, 10L);
        given(songPackApplier.countPlayableTracks(10L, TIME_LIMIT)).willReturn(10); // effective=3
        stubSnapshot(0, List.of(new UserId(1L)));                                    // current DJ = 1
        given(poolService.findIdleBots(2)).willReturn(List.of(new UserId(2L), new UserId(3L)));
        given(botSlotAssigner.reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), anyInt()))
                .willReturn(1, 2);
        // 배치 후 DJ 3 + 리스너 0 → 리스너 목표(3-3=0) no-op
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> liveIdsCaptor = ArgumentCaptor.forClass(List.class);

        service.placeToTarget(ROOM);

        // 신규 2봇: slot 배정(effectiveDjTarget=3) + chunk 복사(djBotCount=3) + 입장 + DJ 등록
        verify(botSlotAssigner, times(2))
                .reclaimAndAssign(eq(ROOM), liveIdsCaptor.capture(), any(Long.class), eq(3));
        verify(songPackApplier, times(2)).applyChunkToBot(any(UserId.class), eq(10L), eq(TIME_LIMIT), anyInt(), eq(3));
        verify(accessCommandService, times(2)).tryEnter(eq(ROOM), any());
        verify(djCommandService, times(2)).enqueueDj(eq(ROOM), any());
        verify(accessCommandService, never()).exit(any());

        // anti-collision 스레딩: 2번째 배정의 liveDjIds 는 1번째 신규 봇(uid=2)을 live 로 포함해야 한다.
        // (liveDj.add(newBot) 가 삭제되면 이 단언이 깨진다 — IT 없이 유닛에서 계약 고정.)
        List<List<Long>> captured = liveIdsCaptor.getAllValues();
        assertThat(captured.get(0)).containsExactly(1L);          // 1번째: 기존 DJ(uid=1)만
        assertThat(captured.get(1)).contains(2L);                 // 2번째: 방금 추가된 uid=2 포함
    }

    @Test
    @DisplayName("(b) 트랙 clamp — djBotCount 5 인데 트랙 2 → effective=2, DJ 2봇만(slot/chunk djBotCount=2)")
    void trackClamp() {
        stubConfig(5, 5, 20L);
        given(songPackApplier.countPlayableTracks(20L, TIME_LIMIT)).willReturn(2); // effective=2
        stubSnapshot(0, List.of());                                                // current DJ = 0
        given(poolService.findIdleBots(2)).willReturn(List.of(new UserId(2L), new UserId(3L)));
        given(botSlotAssigner.reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), anyInt()))
                .willReturn(0, 1);
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(2L); // 리스너 목표 5-5=0

        service.placeToTarget(ROOM);

        // 최대 2봇만, slot/chunk djBotCount 인자는 effective=2 (raw 5 아님)
        verify(poolService).findIdleBots(2); // effective(2) - current(0) = 2 (5 아님)
        verify(botSlotAssigner, times(2)).reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), eq(2));
        verify(botSlotAssigner, never()).reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), eq(5));
        verify(songPackApplier, times(2)).applyChunkToBot(any(UserId.class), eq(20L), eq(TIME_LIMIT), anyInt(), eq(2));
        verify(accessCommandService, times(2)).tryEnter(eq(ROOM), any());
    }

    @Test
    @DisplayName("(c) 리스너 부족 — target 4, dj 2, 현재 DJ 2+리스너 0 → 리스너 2봇 입장만(등록/송팩 없음)")
    void listenerShortfall() {
        stubConfig(4, 2, 30L);
        given(songPackApplier.countPlayableTracks(30L, TIME_LIMIT)).willReturn(10); // effective=2
        stubSnapshot(0, List.of(new UserId(1L), new UserId(2L)));                    // current DJ = 2 (=target, no DJ change)
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(2L); // 2 DJ, 0 리스너
        given(poolService.findIdleBots(2)).willReturn(List.of(new UserId(3L), new UserId(4L)));

        service.placeToTarget(ROOM);

        // DJ 변화 없음
        verify(botSlotAssigner, never()).reclaimAndAssign(any(), anyList(), any(), anyInt());
        verify(songPackApplier, never()).applyChunkToBot(any(), any(), anyInt(), anyInt(), anyInt());
        verify(djCommandService, never()).enqueueDj(any(), any());
        // 리스너 2봇 입장만
        verify(poolService).findIdleBots(2);
        verify(accessCommandService, times(2)).tryEnter(eq(ROOM), any());
        verify(accessCommandService, never()).exit(any());
    }

    @Test
    @DisplayName("(d) 사람 독립성 — humanCount 5 여도 humanCount 0 과 동일한 배치(사람 수 미참조)")
    void humanIndependence() {
        stubConfig(2, 2, 40L);
        given(songPackApplier.countPlayableTracks(40L, TIME_LIMIT)).willReturn(5); // effective=2
        stubSnapshot(5, List.of());                                                // humanCount=5, current DJ=0
        given(poolService.findIdleBots(2)).willReturn(List.of(new UserId(1L), new UserId(2L)));
        given(botSlotAssigner.reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), anyInt()))
                .willReturn(0, 1);
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(2L); // 리스너 목표 2-2=0

        service.placeToTarget(ROOM);

        // humanCount=5 여도 effectiveDjTarget(2) - current(0) = 2봇 그대로 배치 (사람 수와 무관)
        verify(botSlotAssigner, times(2)).reclaimAndAssign(eq(ROOM), anyList(), any(Long.class), eq(2));
        verify(accessCommandService, times(2)).tryEnter(eq(ROOM), any());
        verify(djCommandService, times(2)).enqueueDj(eq(ROOM), any());
    }

    @Test
    @DisplayName("(e) 평형 no-op — 이미 목표 상태(DJ=effective, 리스너=target)면 어떤 작업도 하지 않는다")
    void equilibriumNoOp() {
        stubConfig(4, 2, 50L);
        given(songPackApplier.countPlayableTracks(50L, TIME_LIMIT)).willReturn(10); // effective=2
        stubSnapshot(0, List.of(new UserId(1L), new UserId(2L)));                    // DJ = 2 == effectiveDjTarget
        // 전체 봇 crew = 2 DJ + 2 리스너 = 4, listenerTarget = 4-2 = 2 → currentListeners = 4-2 = 2 == target
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(4L);

        service.placeToTarget(ROOM);

        // ZERO work — 멱등 재실행이 아무 것도 바꾸지 않음을 증명.
        verify(accessCommandService, never()).tryEnter(any(), any());
        verify(accessCommandService, never()).exit(any());
        verify(djCommandService, never()).enqueueDj(any(), any());
        verify(botSlotAssigner, never()).reclaimAndAssign(any(), anyList(), anyLong(), anyInt());
        verify(songPackApplier, never()).applyChunkToBot(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("(f) DJ 초과 — DJ 4, effective 2 → 가장 최근 합류(front) 2봇만 exit, 추가/등록 없음")
    void djExcess() {
        stubConfig(2, 2, 60L);
        given(songPackApplier.countPlayableTracks(60L, TIME_LIMIT)).willReturn(2); // effective=2
        // botUserIdsByJoinedDesc = 가장 최근 합류 먼저 → [4,3,2,1]. 초과 2 → front 2개(uid 4,3) 제거.
        stubSnapshot(0, List.of(new UserId(4L), new UserId(3L), new UserId(2L), new UserId(1L)));
        // 제거 후 DJ 2, 리스너 0 → listenerTarget = 2-2 = 0, currentListeners = 2-2 = 0 → no-op
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(2L);

        ArgumentCaptor<UserId> exitedBot = ArgumentCaptor.forClass(UserId.class);

        service.placeToTarget(ROOM);

        // 정확히 2봇 exit — 그리고 그 2봇은 가장 최근 합류한 uid 4,3 이어야 한다.
        verify(botIdentity, times(2)).runAs(exitedBot.capture(), any(Runnable.class));
        verify(accessCommandService, times(2)).exit(ROOM);
        assertThat(exitedBot.getAllValues().stream().map(UserId::getUid))
                .containsExactly(4L, 3L);
        // 추가 경로는 전혀 타지 않는다.
        verify(botSlotAssigner, never()).reclaimAndAssign(any(), anyList(), anyLong(), anyInt());
        verify(djCommandService, never()).enqueueDj(any(), any());
        verify(accessCommandService, never()).tryEnter(any(), any());
    }

    @Test
    @DisplayName("(g) 리스너 초과 — allCrews-djIds 필터로 리스너만 exit, DJ 봇은 절대 제거 안 함")
    void listenerExcess() {
        stubConfig(3, 2, 70L);
        given(songPackApplier.countPlayableTracks(70L, TIME_LIMIT)).willReturn(10); // effective=2
        UserId djBot1 = new UserId(1L);
        UserId djBot2 = new UserId(2L);
        stubSnapshot(0, List.of(djBot1, djBot2)); // DJ 2 == effective, no DJ change
        // 전체 봇 crew = 2 DJ + 3 리스너 = 5. listenerTarget = 3-2 = 1. currentListeners = 5-2 = 3 → 초과 2.
        given(botPoolQueryRepository.countActiveBotCrewsInRoom(ROOM)).willReturn(5L);
        UserId listener1 = new UserId(11L);
        UserId listener2 = new UserId(12L);
        UserId listener3 = new UserId(13L);
        // 가장 최근 합류 먼저 — DJ 봇도 crew 조회에 섞여 나온다(allCrews - djIds 로 걸러져야 함).
        given(botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(ROOM))
                .willReturn(List.of(listener3, listener2, listener1, djBot2, djBot1));

        ArgumentCaptor<UserId> exitedBot = ArgumentCaptor.forClass(UserId.class);

        service.placeToTarget(ROOM);

        // 정확히 2 리스너 exit, DJ 봇(uid 1,2)은 절대 제거되지 않음.
        verify(botIdentity, times(2)).runAs(exitedBot.capture(), any(Runnable.class));
        verify(accessCommandService, times(2)).exit(ROOM);
        assertThat(exitedBot.getAllValues().stream().map(UserId::getUid))
                .containsExactly(13L, 12L)   // 가장 최근 리스너부터
                .doesNotContain(1L, 2L);     // DJ 봇은 미포함
        // DJ 변화·추가 경로 없음
        verify(djCommandService, never()).enqueueDj(any(), any());
        verify(accessCommandService, never()).tryEnter(any(), any());
    }

    @Test
    @DisplayName("(h) drainResources — 모든 활성 봇 crew(DJ+리스너) exit + slot 비우기, config 는 미변경")
    void drainResources() {
        UserId djBot1 = new UserId(1L);
        UserId djBot2 = new UserId(2L);
        UserId listener1 = new UserId(11L);
        given(botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(ROOM))
                .willReturn(List.of(djBot1, djBot2, listener1)); // 2 DJ + 1 리스너

        service.drainResources(ROOM);

        // 전체 3봇이 봇 신원으로 exit.
        verify(botIdentity, times(3)).runAs(any(UserId.class), any(Runnable.class));
        verify(accessCommandService, times(3)).exit(ROOM);
        // slot row 정리 1회.
        verify(botSlotAssigner).clearRoom(ROOM);
        // config 는 건드리지 않는다(로드도, 저장도 없음 — MANAGED 유지).
        verify(configRepository, never()).findByPartyroomId(anyLong());
        verify(configRepository, never()).save(any());
        // 추가 경로는 전혀 타지 않는다.
        verify(accessCommandService, never()).tryEnter(any(), any());
        verify(djCommandService, never()).enqueueDj(any(), any());
        verify(botSlotAssigner, never()).reclaimAndAssign(any(), anyList(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("게이트 — MANAGED 아니면 아무 것도 하지 않는다")
    void skipWhenNotManaged() {
        PartyroomVirtualDjConfigData off = PartyroomVirtualDjConfigData.builder()
                .partyroomId(ROOM.getId())
                .status(VirtualDjStatus.OFF)
                .targetCount(2).djBotCount(1).songPackId(10L)
                .build();
        given(configRepository.findByPartyroomId(ROOM.getId())).willReturn(Optional.of(off));

        service.placeToTarget(ROOM);

        verify(activeDjSnapshotService, never()).snapshot(any());
        verify(accessCommandService, never()).tryEnter(any(), any());
    }
}
