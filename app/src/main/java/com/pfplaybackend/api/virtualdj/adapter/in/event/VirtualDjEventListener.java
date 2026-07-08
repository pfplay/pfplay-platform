package com.pfplaybackend.api.virtualdj.adapter.in.event;

import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.DjQueueChangedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
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
 * 도메인 이벤트 → {@link VirtualDjOrchestrator#reconcileRoom} 트리거 (Chunk 5).
 *
 * <p>{@link DomainEventRedisRelay} 와 동일한 {@code @TransactionalEventListener(AFTER_COMMIT)} 스타일.
 * AFTER_COMMIT 이 <b>필수</b>인 이유: reconcile 읽기 경로({@code ActiveDjSnapshotService})는
 * {@code @Transactional(readOnly=true)} 로 커밋된 상태를 읽으므로, 입퇴장/DJ 큐 변경이 커밋된 뒤에
 * 트리거돼야 정확한 현재 수를 본다. 각 핸들러는 발행 트랜잭션과 분리된
 * {@code REQUIRES_NEW} 로 실행된다.
 *
 * <p><b>무한루프 가드:</b> reconcile 내부의 봇 enqueue/exit 가 다시 {@link DjQueueChangedEvent} 를
 * 발행 → 또 다른 reconcile 을 부른다. Chunk 4 의 멱등성(목표 수렴 시 add/remove 없음) 덕분에
 * 두 번째 reconcile 은 no-op 으로 즉시 수렴한다. ({@code VirtualDjEventListenerIT} 가 재트리거 시
 * enqueue/exit 가 추가로 일어나지 않음을 spy 로 검증.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualDjEventListener {

    private final VirtualDjOrchestrator orchestrator;
    private final PartyroomVirtualDjConfigRepository configRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCrewAccessed(CrewAccessedEvent event) {
        log.debug("[vdj-event] CrewAccessed → reconcile partyroomId={}", event.getPartyroomId().getId());
        orchestrator.reconcileRoom(event.getPartyroomId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDjQueueChanged(DjQueueChangedEvent event) {
        log.debug("[vdj-event] DjQueueChanged({}) → reconcile partyroomId={}",
                event.getChangeType(), event.getPartyroomId().getId());
        orchestrator.reconcileRoom(event.getPartyroomId());
    }

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
