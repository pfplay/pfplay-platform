package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 가상 DJ 안전망 reconcile cron (Chunk 5) — {@link PartyroomPresenceService#reconcileStalePending} 스타일.
 *
 * <p>이벤트 기반 reconcile 이 유실되거나(이벤트 listener 실패/앱 재시작 중 발생한 변경) anti-flap
 * debounce 로 보류된 제거가 후속 이벤트를 받지 못한 경우를 대비한 저빈도 보정. MANAGED 룸을 훑어
 * 각각 {@link VirtualDjOrchestrator#reconcileRoom} 을 호출한다(멱등이므로 수렴 상태면 no-op).
 *
 * <p>부팅 직후 첫 tick 은 다운타임 동안 어긋난 룸의 복구도 겸한다. 룸별 예외는 격리되어 한 룸의
 * 실패가 전체 sweep 을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjReconcileScheduler {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final VirtualDjOrchestrator orchestrator;
    private final SelfUpdateConfig selfUpdateConfig;
    private final PlaylistSelfUpdateService playlistSelfUpdateService;

    @Scheduled(fixedDelay = 60_000)
    public void reconcileManagedRooms() {
        List<PartyroomVirtualDjConfigData> managed = configRepository.findByStatus(VirtualDjStatus.MANAGED);
        if (managed.isEmpty()) {
            return;
        }
        log.info("[vdj-cron] safety-net sweep over {} MANAGED room(s)", managed.size());
        for (PartyroomVirtualDjConfigData cfg : managed) {
            try {
                orchestrator.reconcileRoom(new PartyroomId(cfg.getPartyroomId()));
            } catch (Exception e) {
                log.warn("[vdj-cron] reconcile failed for partyroomId={} : {}",
                        cfg.getPartyroomId(), e.getMessage());
            }
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
    }
}
