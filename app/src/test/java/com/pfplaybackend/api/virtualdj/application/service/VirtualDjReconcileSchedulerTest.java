package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link VirtualDjReconcileScheduler} 단위 테스트.
 *
 * <p>협력자 모두 mock — 점검 게이트 / self-update 루프 / 반응 루프 로직만 검증한다.
 */
class VirtualDjReconcileSchedulerTest {

    private PartyroomVirtualDjConfigRepository configRepository;
    private SelfUpdateConfig selfUpdateConfig;
    private PlaylistSelfUpdateService playlistSelfUpdateService;
    private VirtualDjReactionConfig reactionConfig;
    private BotReactionService botReactionService;
    private MaintenanceGate maintenanceGate;

    private VirtualDjReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        configRepository = mock(PartyroomVirtualDjConfigRepository.class);
        selfUpdateConfig = mock(SelfUpdateConfig.class);
        playlistSelfUpdateService = mock(PlaylistSelfUpdateService.class);
        reactionConfig = mock(VirtualDjReactionConfig.class);
        botReactionService = mock(BotReactionService.class);
        maintenanceGate = mock(MaintenanceGate.class);

        // 기본값: 점검 아님(게이트 통과) — 개별 테스트에서 필요 시 override
        when(maintenanceGate.isUnderMaintenance()).thenReturn(false);

        scheduler = new VirtualDjReconcileScheduler(
                configRepository, selfUpdateConfig, playlistSelfUpdateService,
                reactionConfig, botReactionService, maintenanceGate);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────────────

    private PartyroomVirtualDjConfigData mockCfg(long partyroomId) {
        PartyroomVirtualDjConfigData cfg = mock(PartyroomVirtualDjConfigData.class);
        when(cfg.getPartyroomId()).thenReturn(partyroomId);
        return cfg;
    }

    // ── self-update 테스트 케이스 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false → 자가갱신 패스 전체 건너뜀")
    void disabled_selfUpdateNeverCalled() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(1L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(2L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(false);

        // when
        scheduler.reconcileManagedRooms();

        // then — 자가갱신 호출 없음
        verify(playlistSelfUpdateService, never()).tryUpdateRoom(any());
    }

    @Test
    @DisplayName("enabled=true → MANAGED 룸 수만큼 tryUpdateRoom 호출")
    void enabled_tryUpdateRoomCalledPerRoom() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(10L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(20L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(true);

        // when
        scheduler.reconcileManagedRooms();

        // then
        verify(playlistSelfUpdateService, times(2)).tryUpdateRoom(any(PartyroomId.class));
    }

    @Test
    @DisplayName("첫 번째 룸에서 self-update 예외 발생 → 두 번째 룸도 호출됨, 메서드 밖으로 전파 안 됨")
    void selfUpdateExceptionIsolated() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(100L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(200L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("LLM 타임아웃"))
                .when(playlistSelfUpdateService).tryUpdateRoom(new PartyroomId(100L));

        // when — 예외가 바깥으로 나오면 안 됨
        assertDoesNotThrow(() -> scheduler.reconcileManagedRooms());

        // then — 두 번째 룸도 호출됨
        verify(playlistSelfUpdateService, times(2)).tryUpdateRoom(any(PartyroomId.class));
    }

    @Test
    @DisplayName("MANAGED 룸 없음 → early return, self-update 미호출")
    void emptyManagedList_earlyReturn() {
        // given
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of());

        // when
        scheduler.reconcileManagedRooms();

        // then
        verify(playlistSelfUpdateService, never()).tryUpdateRoom(any());
        // early return 이므로 isEnabled 체크조차 발생하지 않음
        verify(selfUpdateConfig, never()).isEnabled();
    }

    // ── 반응 루프 테스트 케이스 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("reactionConfig ON → MANAGED 룸마다 tryReact 호출")
    void reactionEnabled_tryReactCalledPerRoom() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(101L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(102L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(false);
        when(reactionConfig.isEnabled()).thenReturn(true);

        // when
        scheduler.reconcileManagedRooms();

        // then — 룸별로 정확한 PartyroomId 로 호출
        verify(botReactionService).tryReact(new PartyroomId(101L));
        verify(botReactionService).tryReact(new PartyroomId(102L));
        verify(botReactionService, times(2)).tryReact(any(PartyroomId.class));
    }

    @Test
    @DisplayName("reactionConfig OFF → tryReact 미호출")
    void reactionDisabled_tryReactNeverCalled() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(1L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(2L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(false);
        when(reactionConfig.isEnabled()).thenReturn(false);

        // when
        scheduler.reconcileManagedRooms();

        // then
        verify(botReactionService, never()).tryReact(any());
    }

    @Test
    @DisplayName("첫 번째 룸에서 반응 예외 발생 → 두 번째 룸도 호출됨, 메서드 밖으로 전파 안 됨")
    void reactionExceptionIsolated() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(100L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(200L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(false);
        when(reactionConfig.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("브로드캐스트 실패"))
                .when(botReactionService).tryReact(new PartyroomId(100L));

        // when — 예외가 바깥으로 나오면 안 됨
        assertDoesNotThrow(() -> scheduler.reconcileManagedRooms());

        // then — 두 번째 룸도 호출됨
        verify(botReactionService, times(2)).tryReact(any(PartyroomId.class));
    }

    // ── 점검 게이트 테스트 케이스 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("점검 중 → self-update·반응 모두 정지, findByStatus 조차 도달 안 함")
    void underMaintenance_neitherLoopRuns() {
        // given
        when(maintenanceGate.isUnderMaintenance()).thenReturn(true);
        when(selfUpdateConfig.isEnabled()).thenReturn(true);
        when(reactionConfig.isEnabled()).thenReturn(true);

        // when
        scheduler.reconcileManagedRooms();

        // then — 게이트 아래로는 아무것도 실행되지 않음
        verify(configRepository, never()).findByStatus(any());
        verify(playlistSelfUpdateService, never()).tryUpdateRoom(any());
        verify(botReactionService, never()).tryReact(any());
    }
}
