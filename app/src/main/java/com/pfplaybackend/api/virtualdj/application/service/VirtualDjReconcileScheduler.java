package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 가상 DJ 저빈도 cron — MANAGED 룸의 플레이리스트 자가갱신 + 봇 반응(좋아요) 루프.
 *
 * <p>고정 2역할 모델에서는 봇 배치가 명시적 batch 트리거(applyConfig·점검 종료·부팅·/revive)로만
 * 이뤄지고 런타임 재등록(안전망 sweep reconcile)은 하지 않는다 — 호스트가 봇을 kick 하면 다음 batch
 * 트리거 전까지 그 상태를 존중한다. 따라서 기존의 안전망 reconcile sweep 은 제거했고, 이 스케줄러는
 * self-update 루프와 반응 루프만 수행한다.
 *
 * <p><b>점검 게이트</b>: {@link MaintenanceGate#isUnderMaintenance()} 이면 메서드 최상단에서 즉시
 * return 하여 점검 중에는 self-update 도 반응도 모두 정지한다.
 *
 * <p><b>self-update 루프</b>: {@code selfUpdateConfig.isEnabled()} 일 때 MANAGED 룸을 훑어 각각
 * {@link PlaylistSelfUpdateService#tryUpdateRoom} 을 호출한다.
 *
 * <p><b>반응 루프</b>: {@code reactionConfig.isEnabled()} 일 때 MANAGED 룸을 훑어 각각
 * {@link BotReactionService#tryReact} 를 호출한다.
 *
 * <p>두 루프 모두 룸별 예외는 격리되어 한 룸의 실패가 전체 루프를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjReconcileScheduler {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final SelfUpdateConfig selfUpdateConfig;
    private final PlaylistSelfUpdateService playlistSelfUpdateService;
    private final VirtualDjReactionConfig reactionConfig;
    private final BotReactionService botReactionService;
    private final MaintenanceGate maintenanceGate;

    @Scheduled(fixedDelay = 60_000)
    public void reconcileManagedRooms() {
        if (maintenanceGate.isUnderMaintenance()) {
            log.debug("[vdj-cron] SKIP_UNDER_MAINTENANCE");
            return;
        }

        List<PartyroomVirtualDjConfigData> managed = configRepository.findByStatus(VirtualDjStatus.MANAGED);
        if (managed.isEmpty()) {
            return;
        }

        if (selfUpdateConfig.isEnabled()) {
            for (PartyroomVirtualDjConfigData cfg : managed) {
                try {
                    playlistSelfUpdateService.tryUpdateRoom(new PartyroomId(cfg.getPartyroomId()));
                } catch (Exception e) {
                    log.warn("[vdj-cron] self-update failed for partyroomId={} : {}",
                            cfg.getPartyroomId(), e.getMessage());
                }
            }
        }

        if (reactionConfig.isEnabled()) {
            for (PartyroomVirtualDjConfigData cfg : managed) {
                try {
                    botReactionService.tryReact(new PartyroomId(cfg.getPartyroomId()));
                } catch (Exception e) {
                    log.warn("[vdj-cron] reaction failed for partyroomId={} : {}",
                            cfg.getPartyroomId(), e.getMessage());
                }
            }
        }
    }
}
