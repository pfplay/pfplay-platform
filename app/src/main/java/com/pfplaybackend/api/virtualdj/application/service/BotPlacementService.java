package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.DjCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import com.pfplaybackend.api.virtualdj.domain.service.TrackDistribution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 고정 2역할 봇 배치 서비스 — 룸의 봇 수를 사람 수와 <b>무관하게</b> 목표에 수렴시킨다.
 *
 * <p>기존의 사람-적응형 수렴 산식(사람이 늘면 봇을 양보)을 대체한다.
 * 두 역할을 각각 고정 개수로 유지한다:
 * <ul>
 *   <li><b>크루(DJ) 봇</b> — 입장 + DJ 등록. 각자 {@code [0, effectiveDjTarget)} slot 을 점유하고
 *       그 slot 의 트랙 파티션만 재생한다. 목표 =
 *       {@code effectiveDjTarget = min(djCount, filteredTrackCount)}.</li>
 *   <li><b>리스너 봇</b> — 입장만(DJ 미등록, slot·송팩 없음). 목표 = {@code targetCount − djCount}.</li>
 * </ul>
 * 두 목표 모두 방 안 사람 수와 무관한 고정값이다({@link ActiveDjSnapshot#humanCount()} 는 읽지 않는다).
 *
 * <p><b>락:</b> {@link #placeToTarget} 은 언락 프리미티브다 — 호출자(오케스트레이터)가 룸 단위 분산락
 * 안에서 부른다. FlapGuard/dwell/debounce 는 사용하지 않는다(고정 목표라 플래핑 원인이 없다).
 *
 * <p><b>멱등·수렴:</b> 현재 수와 목표의 차이만큼만 투입/제거하므로 목표 상태에서 재호출하면 no-op.
 *
 * <p>ArchUnit: {@code *Orchestrator*} 클래스가 아니므로 application 명령/query 서비스, 봇 신원,
 * 풀/송팩/slot 헬퍼, 자기 config repo 접근이 허용된다. {@code ..admin..}/{@code AggregatePort}/
 * {@code MessagePublisher} 는 의존하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotPlacementService {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final PartyroomQueryService partyroomQueryService;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final BotPoolQueryRepository botPoolQueryRepository;
    private final VirtualUserPoolService poolService;
    private final SongPackApplier songPackApplier;
    private final BotSlotAssigner botSlotAssigner;
    private final BotIdentityExecutor botIdentity;
    private final PartyroomAccessCommandService accessCommandService;
    private final DjCommandService djCommandService;

    /**
     * 룸의 봇 배치를 고정 2역할 목표로 수렴시킨다. 언락 프리미티브(호출자가 락으로 감싼다), 멱등.
     *
     * <p>알고리즘:
     * <ol>
     *   <li>config 로드 — MANAGED 아니거나 songPackId 없으면 skip.</li>
     *   <li>{@code effectiveDjTarget = min(djCount, filteredTrackCount)} 계산(트랙 부족 시 clamp).</li>
     *   <li>DJ 봇을 {@code effectiveDjTarget} 로 수렴(slot 배정 + 송팩 조각 복사 + 입장 + DJ 등록).</li>
     *   <li>리스너 봇을 {@code targetCount − djCount} 로 수렴(입장만).</li>
     * </ol>
     */
    public void placeToTarget(PartyroomId partyroomId) {
        // 1) config 로드 및 게이트
        Optional<PartyroomVirtualDjConfigData> optConfig =
                configRepository.findByPartyroomId(partyroomId.getId());
        if (optConfig.isEmpty() || optConfig.get().getStatus() != VirtualDjStatus.MANAGED) {
            log.debug("[placeToTarget] SKIP - partyroomId={}, status={}", partyroomId.getId(),
                    optConfig.map(c -> c.getStatus().name()).orElse("ABSENT"));
            return;
        }
        PartyroomVirtualDjConfigData cfg = optConfig.get();
        if (cfg.getSongPackId() == null) {
            log.warn("[placeToTarget] SKIP_NO_SONG_PACK - partyroomId={}", partyroomId.getId());
            return;
        }

        Long songPackId = cfg.getSongPackId();
        int djCount = cfg.getDjCount();
        int targetCount = cfg.getTargetCount();

        PartyroomData room = partyroomQueryService.getPartyroomById(partyroomId);
        int timeLimitMinutes = room.getPlaybackTimeLimit().getMinutes();

        // 2) 트랙 수에 clamp 된 실제 DJ 목표
        int filtered = songPackApplier.countPlayableTracks(songPackId, timeLimitMinutes);
        int effectiveDjTarget = TrackDistribution.effectiveDjTarget(djCount, filtered);
        if (effectiveDjTarget < djCount) {
            log.warn("[placeToTarget] DJ_COUNT_CLAMPED_BY_TRACKS - partyroomId={}, djCount={}, filteredTracks={}, effectiveDjTarget={}",
                    partyroomId.getId(), djCount, filtered, effectiveDjTarget);
        }

        // step 3 이전 스냅샷 — 현재 DJ 봇(가장 최근 합류 먼저).
        ActiveDjSnapshotService.ActiveDjSnapshot snapshot = activeDjSnapshotService.snapshot(partyroomId);
        List<UserId> djBotsBefore = snapshot.botUserIdsByJoinedDesc();

        log.info("[placeToTarget] partyroomId={}, human={}(무시), djBotsBefore={}, targetCount={}, djCount={}, effectiveDjTarget={}",
                partyroomId.getId(), snapshot.humanCount(), djBotsBefore.size(), targetCount, djCount, effectiveDjTarget);

        // 3) DJ 봇 수렴 → 배치 후의 실제 live DJ 봇 목록을 돌려받는다(step 4 에서 리스너 식별에 사용).
        List<UserId> djBotsAfter = convergeDjBots(partyroomId, songPackId, timeLimitMinutes,
                effectiveDjTarget, djBotsBefore);

        // 4) 리스너 봇 수렴 — 목표는 raw djCount 기준(트랙 clamp 가 would-be DJ 를 리스너로 바꾸지 않는다).
        int listenerTarget = Math.max(0, targetCount - djCount);
        convergeListenerBots(partyroomId, listenerTarget, djBotsAfter);
    }

    /**
     * DJ 봇을 {@code effectiveDjTarget} 로 수렴시키고, 수렴 후의 live DJ 봇 목록을 반환한다.
     *
     * <p>부족 시 idle 봇을 claim 해 slot 배정({@code [0, effectiveDjTarget)}) → 송팩 조각 복사 →
     * 입장 + DJ 등록. 초과 시 가장 최근 합류한 DJ 봇부터 exit. slot/chunk 의 djCount 인자는 반드시
     * {@code effectiveDjTarget} 를 쓴다(slot 범위·트랙 파티션이 실제 DJ 수와 일치하도록).
     *
     * @return 수렴 후 실제 live DJ 봇 UserId 목록(성공적으로 추가/유지된 것만)
     */
    private List<UserId> convergeDjBots(PartyroomId partyroomId, Long songPackId, int timeLimitMinutes,
                                        int effectiveDjTarget, List<UserId> djBotsBefore) {
        // liveDj: 현재 살아있는 DJ 봇 — 추가/제거 성공에 따라 갱신(반환값 겸 slot assigner 입력).
        List<UserId> liveDj = new ArrayList<>(djBotsBefore);
        int currentDj = liveDj.size();

        if (currentDj < effectiveDjTarget) {
            int shortfall = effectiveDjTarget - currentDj;
            List<UserId> idleBots = poolService.findIdleBots(shortfall);
            if (idleBots.size() < shortfall) {
                log.warn("[placeToTarget] INSUFFICIENT_IDLE_BOTS(dj) - partyroomId={}, requested={}, available={}",
                        partyroomId.getId(), shortfall, idleBots.size());
            }
            for (UserId newBot : idleBots) {
                try {
                    List<Long> liveDjIds = new ArrayList<>();
                    for (UserId u : liveDj) {
                        liveDjIds.add(u.getUid());
                    }
                    int slot = botSlotAssigner.reclaimAndAssign(partyroomId, liveDjIds, newBot.getUid(), effectiveDjTarget);
                    songPackApplier.applyChunkToBot(newBot, songPackId, timeLimitMinutes, slot, effectiveDjTarget);
                    botIdentity.runAs(newBot, () -> {
                        accessCommandService.tryEnter(partyroomId, null);
                        djCommandService.enqueueDj(partyroomId, new PlaylistId(poolService.playlistIdOf(newBot)));
                    });
                    liveDj.add(newBot); // 다음 배정을 위해 live 로 등록.
                    log.info("[placeToTarget] BOT_DJ_ADDED - partyroomId={}, botUserId={}, slot={}",
                            partyroomId.getId(), newBot.getUid(), slot);
                } catch (Exception e) {
                    log.warn("[placeToTarget] BOT_DJ_ADD_FAILED - partyroomId={}, botUserId={}, reason={}",
                            partyroomId.getId(), newBot.getUid(), e.getMessage());
                }
            }
        } else if (currentDj > effectiveDjTarget) {
            int excess = currentDj - effectiveDjTarget;
            // 가장 최근 합류(리스트 앞쪽)부터 제거.
            List<UserId> toRemove = new ArrayList<>(djBotsBefore.subList(0, Math.min(excess, djBotsBefore.size())));
            for (UserId bot : toRemove) {
                try {
                    botIdentity.runAs(bot, () -> accessCommandService.exit(partyroomId));
                    liveDj.remove(bot);
                    log.info("[placeToTarget] BOT_DJ_REMOVED - partyroomId={}, botUserId={}",
                            partyroomId.getId(), bot.getUid());
                } catch (Exception e) {
                    log.warn("[placeToTarget] BOT_DJ_REMOVE_FAILED - partyroomId={}, botUserId={}, reason={}",
                            partyroomId.getId(), bot.getUid(), e.getMessage());
                }
            }
        }
        return liveDj;
    }

    /**
     * 리스너 봇을 {@code listenerTarget} 로 수렴시킨다(입장만, DJ 미등록·slot·송팩 없음).
     *
     * <p>현재 리스너 수 = (룸의 전체 활성 봇 crew) − (수렴 후 live DJ 봇). step 3 이후의 값을 읽는데,
     * 봇 명령 경로는 동일 트랜잭션(이벤트 경로 REQUIRES_NEW 안) 또는 독립 커밋(cron 경로)이라 후속
     * 읽기에 반영되어 있다. {@code djBotsAfter} 로 빼면 새로 투입된 DJ 봇을 리스너로 오분류하지 않는다.
     */
    private void convergeListenerBots(PartyroomId partyroomId, int listenerTarget, List<UserId> djBotsAfter) {
        Set<Long> djIds = new HashSet<>();
        for (UserId u : djBotsAfter) {
            djIds.add(u.getUid());
        }

        long totalBotCrews = botPoolQueryRepository.countActiveBotCrewsInRoom(partyroomId);
        // max(0,…) 대칭 가드: 동일-tx 가시성 불변식이 깨져 음수가 나오더라도 over-provision 대신
        // under-count(다음 사이클 self-heal) 로 실패하도록 한다(listenerTarget 과 동일한 하한).
        int currentListeners = Math.max(0, (int) totalBotCrews - djBotsAfter.size());

        log.info("[placeToTarget] listeners - partyroomId={}, totalBotCrews={}, djNow={}, currentListeners={}, listenerTarget={}",
                partyroomId.getId(), totalBotCrews, djBotsAfter.size(), currentListeners, listenerTarget);

        if (currentListeners < listenerTarget) {
            int shortfall = listenerTarget - currentListeners;
            List<UserId> idleBots = poolService.findIdleBots(shortfall);
            if (idleBots.size() < shortfall) {
                log.warn("[placeToTarget] INSUFFICIENT_IDLE_BOTS(listener) - partyroomId={}, requested={}, available={}",
                        partyroomId.getId(), shortfall, idleBots.size());
            }
            for (UserId newBot : idleBots) {
                try {
                    // 리스너: 입장만 — enqueueDj/slot/송팩 없음.
                    botIdentity.runAs(newBot, () -> accessCommandService.tryEnter(partyroomId, null));
                    log.info("[placeToTarget] BOT_LISTENER_ADDED - partyroomId={}, botUserId={}",
                            partyroomId.getId(), newBot.getUid());
                } catch (Exception e) {
                    log.warn("[placeToTarget] BOT_LISTENER_ADD_FAILED - partyroomId={}, botUserId={}, reason={}",
                            partyroomId.getId(), newBot.getUid(), e.getMessage());
                }
            }
        } else if (currentListeners > listenerTarget) {
            int excess = currentListeners - listenerTarget;
            // 리스너 집합 = 전체 봇 crew − DJ 봇. 가장 최근 합류 리스너부터 제거.
            List<UserId> allCrews = botPoolQueryRepository.findActiveBotCrewUserIdsByJoinedDesc(partyroomId);
            List<UserId> listeners = new ArrayList<>();
            for (UserId u : allCrews) {
                if (!djIds.contains(u.getUid())) {
                    listeners.add(u);
                }
            }
            int removed = 0;
            for (UserId bot : listeners) {
                if (removed >= excess) {
                    break;
                }
                try {
                    botIdentity.runAs(bot, () -> accessCommandService.exit(partyroomId));
                    log.info("[placeToTarget] BOT_LISTENER_REMOVED - partyroomId={}, botUserId={}",
                            partyroomId.getId(), bot.getUid());
                    removed++;
                } catch (Exception e) {
                    log.warn("[placeToTarget] BOT_LISTENER_REMOVE_FAILED - partyroomId={}, botUserId={}, reason={}",
                            partyroomId.getId(), bot.getUid(), e.getMessage());
                }
            }
        }
    }
}
