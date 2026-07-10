package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VirtualCrewOrchestrator} 구현 — 룸 단위 분산락으로 봇 배치 명령을 직렬화하는 얇은 파사드다.
 *
 * <p><b>reconcile</b> 은 {@link BotPlacementService#placeToTarget} 에 위임한다 — 고정 2역할
 * (크루 DJ + 리스너) 배치로, 사람 수와 무관하게 목표 개수를 유지한다(사람-적응형 산식 제거).
 *
 * <p><b>drain</b>(어드민 OFF 전환)은 {@link BotPlacementService#drainResources} 에 위임한다 —
 * 룸의 모든 봇 crew 를 봇 신원 exit 로 즉시 제거하고 slot 을 비운다(config 전환은 호출자 담당).
 *
 * <p><b>의존성:</b> 이 {@code *Orchestrator*} 는 {@link DistributedLockExecutor} 와
 * {@link BotPlacementService} 만 의존한다 — 락으로 감싸 배치 프리미티브를 호출하는 것 외의 로직이 없다.
 * crew/dj/playback 의 도메인 {@code *Repository}/{@code *AggregatePort} 를 주입하지 않으며
 * {@code ..admin..} 도 import 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualCrewOrchestratorImpl implements VirtualCrewOrchestrator {

    private final DistributedLockExecutor lock;
    private final BotPlacementService botPlacementService;

    /**
     * <p><b>트랜잭션 경계:</b> per-call(룸 단위) {@code @Transactional(REQUIRED)}. 이벤트/부팅 경로
     * (이벤트 리스너 AFTER_COMMIT · 부팅 리바이버 — 주변 트랜잭션 없음)에서 호출되면 이 메서드가
     * 자체 트랜잭션을 시작해 하위 {@code placeToTarget} 의 쓰기(crew enter/DJ enqueue/slot delete)가
     * 확정된다. 어드민 경로({@link VirtualCrewAdminService})처럼 주변 트랜잭션이 있으면 join 한다(동작 불변).
     * 트랜잭션이 락 획득을 감싸는 순서는 어드민 경로 기존 순서와 동일하다.
     */
    @Override
    @Transactional
    public void reconcileRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualcrew:" + partyroomId.getId(), () -> {
            botPlacementService.placeToTarget(partyroomId);
            return null;
        });
    }

    /** {@link #reconcileRoom} 과 동일한 per-call(룸 단위) {@code @Transactional} 경계 — 이벤트/부팅 경로의 drain 쓰기 확정. */
    @Override
    @Transactional
    public void drainRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualcrew:" + partyroomId.getId(), () -> {
            botPlacementService.drainResources(partyroomId);
            return null;
        });
    }

    /**
     * {@link #reconcileRoom} 과 동일한 per-call(룸 단위) {@code @Transactional} 경계.
     *
     * <p><b>락 합성 금지:</b> {@link DistributedLockExecutor} 는 비재진입(점유 중이면 무음 skip)이므로
     * 락이 걸린 {@code drainRoom}/{@code reconcileRoom} 을 이어 부르지 않고, 락 1회 안에서
     * 언락 프리미티브({@code drainResources}→{@code placeToTarget})를 직접 호출한다.
     *
     * <p><b>복구 모델:</b> place 단계 예외 시 — {@code /replace} 엔드포인트 경로는 config 무변경(MANAGED
     * 유지)이라 revive 재시도로 복구, applyConfig 자동 replace 경로는 같은 트랜잭션이라 송팩 변경까지
     * 롤백(기존 reconcile 실패 시맨틱과 동일).
     *
     * <p>⚠️ 락 TTL 대비 임계구간이 drain+place 로 길어진다 — placeToTarget 단독도 초과 가능한
     * 선재 리스크 클래스로, 본 변경에서 확장하지 않는다.
     */
    @Override
    @Transactional
    public void replaceRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualcrew:" + partyroomId.getId(), () -> {
            botPlacementService.drainResources(partyroomId);
            botPlacementService.placeToTarget(partyroomId);
            return null;
        });
    }
}
