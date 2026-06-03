package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
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
 * <p>협력자 4개 모두 mock — 스케줄러 로직만 검증한다.
 */
class VirtualDjReconcileSchedulerTest {

    private PartyroomVirtualDjConfigRepository configRepository;
    private VirtualDjOrchestrator orchestrator;
    private SelfUpdateConfig selfUpdateConfig;
    private PlaylistSelfUpdateService playlistSelfUpdateService;

    private VirtualDjReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        configRepository = mock(PartyroomVirtualDjConfigRepository.class);
        orchestrator = mock(VirtualDjOrchestrator.class);
        selfUpdateConfig = mock(SelfUpdateConfig.class);
        playlistSelfUpdateService = mock(PlaylistSelfUpdateService.class);

        scheduler = new VirtualDjReconcileScheduler(
                configRepository, orchestrator, selfUpdateConfig, playlistSelfUpdateService);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────────────

    private PartyroomVirtualDjConfigData mockCfg(long partyroomId) {
        PartyroomVirtualDjConfigData cfg = mock(PartyroomVirtualDjConfigData.class);
        when(cfg.getPartyroomId()).thenReturn(partyroomId);
        return cfg;
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false → 자가갱신 패스 전체 건너뜀, reconcile 은 정상 실행")
    void disabled_selfUpdateNeverCalled() {
        // given
        PartyroomVirtualDjConfigData cfg1 = mockCfg(1L);
        PartyroomVirtualDjConfigData cfg2 = mockCfg(2L);
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of(cfg1, cfg2));
        when(selfUpdateConfig.isEnabled()).thenReturn(false);

        // when
        scheduler.reconcileManagedRooms();

        // then — reconcile 은 2회 실행
        verify(orchestrator, times(2)).reconcileRoom(any());
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
    @DisplayName("MANAGED 룸 없음 → early return, orchestrator 와 self-update 모두 미호출")
    void emptyManagedList_earlyReturn() {
        // given
        when(configRepository.findByStatus(VirtualDjStatus.MANAGED)).thenReturn(List.of());

        // when
        scheduler.reconcileManagedRooms();

        // then
        verify(orchestrator, never()).reconcileRoom(any());
        verify(playlistSelfUpdateService, never()).tryUpdateRoom(any());
        // early return 이므로 isEnabled 체크조차 발생하지 않음
        verify(selfUpdateConfig, never()).isEnabled();
    }
}
