package com.pfplaybackend.api.virtualdj.adapter.in.event;

import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 가상 DJ 도메인 이벤트 리스너 — 룸 종료 시 config OFF 전환만 담당한다.
 *
 * <p>고정 2역할 모델에서는 봇 배치가 명시적 batch 트리거(applyConfig·점검 종료·부팅·/revive)로만
 * 이뤄진다. 과거 크루 입퇴장({@code CrewAccessedEvent})·DJ 큐 변경({@code DjQueueChangedEvent})마다
 * reconcile 을 재트리거하던 <b>런타임 재등록</b> 리스너는 제거했다 — 호스트가 봇을 kick 하면 다음 batch
 * 트리거 전까지 그 상태를 존중해야 하기 때문이다.
 *
 * <p>{@link DomainEventRedisRelay} 와 동일한 {@code @TransactionalEventListener(AFTER_COMMIT)} 스타일로,
 * 발행 트랜잭션과 분리된 {@code REQUIRES_NEW} 로 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualDjEventListener {

    private final PartyroomVirtualDjConfigRepository configRepository;

    /**
     * 룸 종료 시 config 정리 — 종료된 룸은 reconcile 하지 않고 {@code turnOff} 로 OFF 전환한다.
     * config 가 없으면(가상 DJ 미사용 룸) 아무 것도 하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTerminated(PartyroomTerminatedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        Optional<PartyroomVirtualDjConfigData> optConfig = configRepository.findByPartyroomId(partyroomId);
        if (optConfig.isEmpty()) {
            return;
        }
        PartyroomVirtualDjConfigData cfg = optConfig.get();
        cfg.turnOff();
        configRepository.save(cfg);
        log.info("[vdj-event] PartyroomTerminated → config OFF partyroomId={}", partyroomId);
    }
}
