package com.pfplaybackend.api.virtualdj.application.service;

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
 * 가상 DJ 저빈도 cron — MANAGED 룸의 플레이리스트 자가갱신 루프.
 *
 * <p>고정 2역할 모델에서는 봇 배치가 명시적 batch 트리거(applyConfig·점검 종료·부팅·/revive)로만
 * 이뤄지고 런타임 재등록(안전망 sweep reconcile)은 하지 않는다 — 호스트가 봇을 kick 하면 다음 batch
 * 트리거 전까지 그 상태를 존중한다. 따라서 기존의 안전망 reconcile sweep 은 제거했고, 이 스케줄러는
 * self-update 루프만 수행한다.
 *
 * <p>{@code selfUpdateConfig.isEnabled()} 일 때 MANAGED 룸을 훑어 각각
 * {@link PlaylistSelfUpdateService#tryUpdateRoom} 을 호출한다. 룸별 예외는 격리되어 한 룸의 실패가
 * 전체 루프를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjReconcileScheduler {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final SelfUpdateConfig selfUpdateConfig;
    private final PlaylistSelfUpdateService playlistSelfUpdateService;

    @Scheduled(fixedDelay = 60_000)
    public void reconcileManagedRooms() {
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
    }
}
