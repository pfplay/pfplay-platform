package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link VirtualDjOrchestrator} 구현 — 봇 임퍼소네이션으로 룸의 봇 배치를 목표에 수렴시킨다.
 *
 * <p><b>reconcile</b> 은 {@link BotPlacementService#placeToTarget} 에 위임한다 — 고정 2역할
 * (크루 DJ + 리스너) 배치로, 사람 수와 무관하게 목표 개수를 유지한다(사람-적응형 산식 제거).
 *
 * <p><b>drain</b>(어드민 OFF 전환)은 여기서 직접 수행한다 — 룸의 모든 봇 DJ 를 봇 신원 exit 로
 * 즉시 제거하고 {@link FlapGuard} 항목을 정리한다.
 *
 * <p><b>의존성 제약(Chunk 6 ArchUnit 사전 충족):</b> 이 {@code *Orchestrator*} 는 application
 * 명령/query 서비스, {@link BotIdentityExecutor}, {@link BotPlacementService},
 * {@link ActiveDjSnapshotService}, {@link DistributedLockExecutor}, {@link FlapGuard} 만 의존한다.
 * crew/dj/playback 의 도메인 {@code *Repository}/{@code *AggregatePort} 를 주입하지 않으며
 * {@code ..admin..} 도 import 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjOrchestratorImpl implements VirtualDjOrchestrator {

    private final DistributedLockExecutor lock;
    private final BotPlacementService botPlacementService;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final BotIdentityExecutor botIdentity;
    private final PartyroomAccessCommandService accessCommandService;
    private final FlapGuard flapGuard;

    @Override
    public void reconcileRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualdj:" + partyroomId.getId(), () -> {
            botPlacementService.placeToTarget(partyroomId);
            return null;
        });
    }

    @Override
    public void drainRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualdj:" + partyroomId.getId(), () -> {
            doDrain(partyroomId);
            return null;
        });
    }

    private void doDrain(PartyroomId partyroomId) {
        ActiveDjSnapshotService.ActiveDjSnapshot snapshot = activeDjSnapshotService.snapshot(partyroomId);
        List<UserId> bots = snapshot.botUserIdsByJoinedDesc();
        log.info("[drain] partyroomId={}, botsToRemove={}", partyroomId.getId(), bots.size());

        for (UserId bot : bots) {
            // 어드민 drain 은 dwell/debounce 를 건너뛴다(canRemoveBot/shouldRemove 미적용) — 단,
            // 제거는 봇 신원으로 동일한 exit 명령 경로(path A)를 거쳐 가드/캐스케이드를 우회하지 않는다.
            // 단건 실패(예: crew 행 소실로 INVALID_ACTIVE_ROOM)가 나머지 봇 제거를 중단하지 않도록
            // 개별 봇을 try/catch 로 격리한다(best-effort drain — config 는 이미 OFF 확정).
            try {
                botIdentity.runAs(bot, () -> accessCommandService.exit(partyroomId));
                log.info("[drain] BOT_REMOVED - partyroomId={}, botUserId={}", partyroomId.getId(), bot.getUid());
            } catch (Exception e) {
                log.warn("[drain] BOT_REMOVE_FAILED - partyroomId={}, botUserId={}, reason={}",
                        partyroomId.getId(), bot.getUid(), e.getMessage());
            }
            // 성공/실패 무관하게 FlapGuard 항목은 정리 — 다음 MANAGED 전환 시 새로 측정되도록.
            flapGuard.clearAdded(bot);
        }
        // 제거 의도 타이머도 정리 — 다음 MANAGED 전환 시 debounce 가 처음부터 측정되도록(FlapGuard 누수 방지).
        flapGuard.clearRemovalIntent(partyroomId);
    }
}
