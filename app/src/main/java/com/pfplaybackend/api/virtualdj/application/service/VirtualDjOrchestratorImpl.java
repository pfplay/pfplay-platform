package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.DjCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import com.pfplaybackend.api.virtualdj.domain.service.DesiredBotCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link VirtualDjOrchestrator} 구현 — 봇 임퍼소네이션으로 룸의 봇 DJ 수를 목표에 수렴시킨다.
 *
 * <p><b>의존성 제약(Chunk 6 ArchUnit 사전 충족):</b> 이 {@code *Orchestrator*} 는 application
 * 명령/query 서비스, {@link BotIdentityExecutor}, {@link VirtualUserPoolService},
 * {@link SongPackApplier}, {@link DistributedLockExecutor}, 순수 {@link DesiredBotCalculator},
 * 그리고 설정 읽기용 {@link PartyroomVirtualDjConfigRepository} 만 의존한다. crew/dj/playback 의
 * 도메인 {@code *Repository}/{@code *AggregatePort} 를 주입하지 않으며 {@code ..admin..} 도 import 하지 않는다.
 * 활성 DJ 사람/봇 분리 같은 읽기는 query 서비스({@link ActiveDjSnapshotService})를 거친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualDjOrchestratorImpl implements VirtualDjOrchestrator {

    private final DistributedLockExecutor lock;
    private final PartyroomVirtualDjConfigRepository configRepository;
    private final PartyroomQueryService partyroomQueryService;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final VirtualUserPoolService poolService;
    private final SongPackApplier songPackApplier;
    private final BotIdentityExecutor botIdentity;
    private final PartyroomAccessCommandService accessCommandService;
    private final DjCommandService djCommandService;
    private final FlapGuard flapGuard;
    private final Clock clock;

    @Override
    public void reconcileRoom(PartyroomId partyroomId) {
        lock.performTaskWithLock("virtualdj:" + partyroomId.getId(), () -> {
            doReconcile(partyroomId);
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

    private void doReconcile(PartyroomId partyroomId) {
        Optional<PartyroomVirtualDjConfigData> optConfig =
                configRepository.findByPartyroomId(partyroomId.getId());
        if (optConfig.isEmpty() || optConfig.get().getStatus() != VirtualDjStatus.MANAGED) {
            // OFF / FROZEN / 미설정 → 아무 것도 하지 않는다.
            log.debug("[reconcile] SKIP - partyroomId={}, status={}", partyroomId.getId(),
                    optConfig.map(c -> c.getStatus().name()).orElse("ABSENT"));
            return;
        }
        PartyroomVirtualDjConfigData cfg = optConfig.get();

        if (cfg.getSongPackId() == null) {
            log.warn("[reconcile] SKIP_NO_SONG_PACK - partyroomId={}", partyroomId.getId());
            return;
        }

        ActiveDjSnapshotService.ActiveDjSnapshot snapshot = activeDjSnapshotService.snapshot(partyroomId);
        int humanCount = snapshot.humanCount();
        int botCount = snapshot.botCount();
        int desired = DesiredBotCalculator.desiredBot(humanCount, cfg.getTargetCount(), cfg.getCompanionFloor());

        log.info("[reconcile] partyroomId={}, human={}, bot={}, target={}, floor={}, desired={}",
                partyroomId.getId(), humanCount, botCount, cfg.getTargetCount(), cfg.getCompanionFloor(), desired);

        if (botCount < desired) {
            // 추가는 즉시 — 음악 연속성 우선(anti-flap 게이팅 없음).
            addBots(partyroomId, cfg, desired - botCount);
            // 추가 방향이면 제거 의도는 더 이상 유효하지 않다.
            flapGuard.clearRemovalIntent(partyroomId);
        } else if (botCount > desired) {
            removeBots(partyroomId, snapshot.botUserIdsByJoinedDesc(), botCount - desired);
        } else {
            // botCount == desired → no-op (멱등 수렴). 제거가 더는 필요 없으므로 의도 해제.
            flapGuard.clearRemovalIntent(partyroomId);
        }
    }

    private void addBots(PartyroomId partyroomId, PartyroomVirtualDjConfigData cfg, int howMany) {
        // getPartyroomById reads no ThreadLocal AuthContext — safe to call outside the bot-identity block
        PartyroomData room = partyroomQueryService.getPartyroomById(partyroomId);
        int timeLimitMinutes = room.getPlaybackTimeLimit().getMinutes();

        List<UserId> idleBots = poolService.findIdleBots(howMany);
        if (idleBots.size() < howMany) {
            // idle 풀 부족 — 가능한 만큼만 투입한다(나머지는 다음 reconcile 에서 풀이 보충되면 처리).
            log.warn("[reconcile] INSUFFICIENT_IDLE_BOTS - partyroomId={}, requested={}, available={}",
                    partyroomId.getId(), howMany, idleBots.size());
        }

        for (UserId bot : idleBots) {
            // 1) 송 팩을 봇 playlist 에 적용 (룸 시간제한 필터) — 봇 신원 밖에서 수행해도 무방하나
            //    enqueue 전에 비어 있지 않은 playlist 를 보장해야 한다.
            songPackApplier.applyToBot(bot, cfg.getSongPackId(), timeLimitMinutes);

            // 2) 봇 신원으로 실유저와 동일한 명령 경로 호출 (입장 → DJ 등록).
            botIdentity.runAs(bot, () -> {
                accessCommandService.tryEnter(partyroomId, null);
                djCommandService.enqueueDj(partyroomId, new PlaylistId(poolService.playlistIdOf(bot)));
            });
            // anti-flap: 투입 시각 기록 — min dwell 동안 제거 보호.
            flapGuard.markAdded(bot, Instant.now(clock));
            log.info("[reconcile] BOT_ADDED - partyroomId={}, botUserId={}", partyroomId.getId(), bot.getUid());
        }
    }

    private void removeBots(PartyroomId partyroomId, List<UserId> botsByJoinedDesc, int howMany) {
        Instant now = Instant.now(clock);

        // anti-flap (1): 룸 단위 제거 의도가 debounce 를 넘기지 못했으면 이번 사이클은 제거하지 않는다.
        // (의도 시각은 shouldRemove 가 첫 호출 시 기록 — 다음 이벤트/안전망이 재시도한다.)
        if (!flapGuard.shouldRemove(partyroomId, now)) {
            log.info("[reconcile] REMOVE_DEBOUNCED - partyroomId={}, want={}", partyroomId.getId(), howMany);
            return;
        }

        int removed = 0;
        for (UserId bot : botsByJoinedDesc) {
            if (removed >= howMany) break;
            // anti-flap (2): 최소 체류시간을 채우지 못한 봇은 건너뛴다(다음 사이클 재시도).
            if (!flapGuard.canRemoveBot(bot, now)) {
                log.debug("[reconcile] REMOVE_DWELL_PROTECTED - partyroomId={}, botUserId={}",
                        partyroomId.getId(), bot.getUid());
                continue;
            }
            // 봇 신원으로 exit — handleDjQueueOnLeave 가 DJ 큐 정리 + (현재 DJ 였다면) playback skip 처리.
            botIdentity.runAs(bot, () -> accessCommandService.exit(partyroomId));
            flapGuard.clearAdded(bot);
            log.info("[reconcile] BOT_REMOVED - partyroomId={}, botUserId={}", partyroomId.getId(), bot.getUid());
            removed++;
        }
        if (removed == 0) {
            log.info("[reconcile] REMOVE_ALL_DWELL_PROTECTED - partyroomId={}, want={}",
                    partyroomId.getId(), howMany);
        }
    }
}
