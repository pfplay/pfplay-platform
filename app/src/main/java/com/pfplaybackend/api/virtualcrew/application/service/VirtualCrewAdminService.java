package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.PoolPlacementRow;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 어드민 가상 DJ 운영 서비스 — config 적용/drain + 단건/일괄 reconcile·replace + live status 조회.
 *
 * <p>config 전환은 도메인 메서드({@link PartyroomVirtualCrewConfigData#applyManaged}/{@code turnOff})를
 * 통해서만 수행하고, 봇 투입/제거는 모두 {@link VirtualCrewOrchestrator}(봇 신원 path A)에 위임한다.
 * 이 서비스는 도메인 crew/dj/playback persistence 를 직접 건드리지 않는다.
 *
 * <p>트랜잭션 경계: config save + reconcile/drain 을 한 트랜잭션에 둔다 — reconcile/drain 은
 * 봇 신원으로 {@code @Transactional(REQUIRED)} 명령 서비스를 호출하므로 같은 트랜잭션에 join 된다
 * (orchestrator IT 와 동일 패턴: apply config → 같은 tx 안에서 reconcile → 봇 enqueue 가 즉시 반영).
 * 분산 락은 reconcile/drain 의 동시 호출을 직렬화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualCrewAdminService {

    private final PartyroomVirtualCrewConfigRepository configRepository;
    private final VirtualCrewOrchestrator orchestrator;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final VirtualUserPoolService poolService;
    private final BotPoolQueryRepository botPoolQueryRepository;
    private final PartyroomQueryService partyroomQueryService;
    private final SongPackApplier songPackApplier;

    /**
     * applyBulk 의 per-room 격리를 위한 self-proxy 참조.
     * {@code @Lazy} 로 순환 의존성을 방지하고, setter 주입으로 Spring AOP 프록시를 통해
     * {@link #applyConfig} 의 {@code @Transactional} 경계가 룸마다 독립적으로 적용되도록 한다.
     */
    @Setter(onMethod_ = {@Autowired, @Lazy})
    private VirtualCrewAdminService self;

    // ── pool ──

    public void provisionPool(int count) {
        poolService.provision(count);
    }

    /**
     * 선택 봇들을 풀에서 제거(탈퇴 soft-delete)한다 — provision 의 역연산(로스터 다중 선택 삭제).
     *
     * <p><b>유휴 전용 가드:</b> 요청 봇 중 하나라도 현재 방에 배치돼 있으면 전체를 거부한다
     * ({@link VirtualCrewException#BOT_PLACED_CANNOT_REMOVE}). 배치된 봇을 탈퇴시키면 다음 reconcile
     * 까지 방에 남는 창(window)이 생기므로, 먼저 해당 방을 리소스 회수/재배치하도록 강제한다.
     * 부분 제거 대신 all-or-nothing 으로 혼선을 막는다.
     *
     * <p>로스터에 없는 id(비-봇/미존재/이미 탈퇴)는 멱등적으로 스킵한다. 반환값은 실제 탈퇴된 봇 목록.
     */
    @Transactional
    public BotRemovalResult removeBots(List<Long> botUserIds) {
        if (botUserIds == null || botUserIds.isEmpty()) {
            return new BotRemovalResult(0, List.of());
        }
        // 중복 제거 후 실제 봇(is_dummy·non-withdrawn)만 남긴다 — 비봇/미존재/이미 탈퇴 id 는 멱등 스킵.
        List<Long> validBots = botPoolQueryRepository.filterBotUserIds(
                new ArrayList<>(new LinkedHashSet<>(botUserIds)));
        if (validBots.isEmpty()) {
            return new BotRemovalResult(0, List.of());
        }
        // 활성 crew 로 배치된 봇이 하나라도 있으면 전체 거부(먼저 리소스 회수/재배치). 파티룸 행 존재가
        // 아니라 활성 crew 존재(권위 신호)로 판정 — orphan 방 잔류 봇도 배치로 본다. all-or-nothing.
        if (!botPoolQueryRepository.filterPlacedBotUserIds(validBots).isEmpty()) {
            throw ExceptionCreator.create(VirtualCrewException.BOT_PLACED_CANNOT_REMOVE);
        }
        for (Long id : validBots) {
            poolService.withdrawBot(new UserId(id));
        }
        log.info("[VirtualCrewAdmin.removeBots] requested={} removed={}", botUserIds.size(), validBots.size());
        return new BotRemovalResult(validBots.size(), validBots);
    }

    /** 봇 제거 결과 — 실제 탈퇴된 봇 수 + userId 목록(로스터에 없던 id 는 제외). */
    public record BotRemovalResult(int removed, List<Long> removedUserIds) {}

    /** 봇 닉네임 변경(파티룸 노출명 교체) — 검증·UNIQUE 는 pool→provision→admin 경로에서 수행. */
    @Transactional
    public void renameBot(Long botUserId, String nickname) {
        poolService.renameBot(new UserId(botUserId), nickname);
        log.info("[VirtualCrewAdmin.renameBot] botUserId={} nickname={}", botUserId, nickname);
    }

    /** 봇 풀 전체 요약 — 전체/idle 수 + 파티룸별 배치 현황. */
    @Transactional(readOnly = true)
    public PoolSummary poolSummary() {
        long total = botPoolQueryRepository.countBots();
        long idle = botPoolQueryRepository.countIdleBots();
        List<PoolSummary.Placement> placed = botPoolQueryRepository.findPlacements().stream()
                .map(row -> new PoolSummary.Placement(row.partyroomId(), row.partyroomTitle(), row.botCount()))
                .toList();
        return new PoolSummary(total, idle, placed);
    }

    // ── per-room config ──

    /** 단일 룸 config 적용 후 MANAGED 면 reconcile(송팩 변경 시 replace), OFF 면 drain 을 같은 트랜잭션에서 트리거. */
    @Transactional
    public void applyConfig(PartyroomId partyroomId, VirtualCrewStatus status, Integer targetCount,
                            Integer djBotCount, Long songPackId) {
        PartyroomVirtualCrewConfigData cfg = loadOrCreate(partyroomId);
        Long previousSongPackId = cfg.getSongPackId();
        applyStatus(cfg, status, targetCount, djBotCount, songPackId);
        // saveAndFlush: reconcile/drain 의 봇 명령 경로가 영속성 컨텍스트를 clear 할 수 있어,
        // config 변경을 즉시 DB 에 확정한 뒤 reconcile(자기 config 재조회)/drain 을 트리거한다.
        configRepository.saveAndFlush(cfg);
        log.info("[VirtualCrewAdmin.applyConfig] partyroomId={} status={} target={} djBotCount={} songPackId={}",
                partyroomId.getId(), status, targetCount, djBotCount, songPackId);

        if (status == VirtualCrewStatus.MANAGED) {
            if (!Objects.equals(previousSongPackId, songPackId)) {
                // 송팩 변경 — 봇은 배치 시점 스냅샷을 재생하므로 reconcile(카운트 수렴)만으로는
                // 기존 봇이 옛 팩을 계속 튼다(무음 no-op). 회수→재배치로 새 팩 스냅샷을 강제한다.
                orchestrator.replaceRoom(partyroomId);
            } else {
                orchestrator.reconcileRoom(partyroomId);
            }
        } else if (status == VirtualCrewStatus.OFF) {
            orchestrator.drainRoom(partyroomId);
        }
    }

    /** 룸 비우기 — config OFF + 모든 봇 제거(drain, path A). */
    @Transactional
    public void drain(PartyroomId partyroomId) {
        PartyroomVirtualCrewConfigData cfg = loadOrCreate(partyroomId);
        cfg.turnOff();
        // OFF 를 즉시 flush — drainRoom 의 봇 exit 경로가 영속성 컨텍스트를 clear 할 수 있어,
        // 미flush 된 dirty 변경이 detach 되어 유실되는 것을 방지한다(DB 에 OFF 확정).
        configRepository.saveAndFlush(cfg);
        orchestrator.drainRoom(partyroomId);
        log.info("[VirtualCrewAdmin.drain] partyroomId={}", partyroomId.getId());
    }

    /**
     * 리소스 회수 — 봇 제거하되 config MANAGED 유지({@code /drain} 과 달리 OFF 전환 안 함).
     * {@code /revive}·점검종료로 복원 가능.
     */
    @Transactional
    public void drainResources(PartyroomId partyroomId) {
        orchestrator.drainRoom(partyroomId);
        log.info("[VirtualCrewAdmin.drainResources] partyroomId={}", partyroomId.getId());
    }

    /** 부활 — MANAGED 방을 목표로 재배치(placeToTarget). config 미변경. */
    @Transactional
    public void revive(PartyroomId partyroomId) {
        orchestrator.reconcileRoom(partyroomId);
        log.info("[VirtualCrewAdmin.revive] partyroomId={}", partyroomId.getId());
    }

    /**
     * 재배치 — 봇 전원 회수 후 현재 config·송팩 기준으로 즉시 재배치. config 미변경(MANAGED 유지).
     * 용도: 송팩 곡 구성 편집 반영, djBotCount 변경 후 파티션 재분배, 자기갱신 드리프트 리셋.
     */
    @Transactional
    public void replace(PartyroomId partyroomId) {
        orchestrator.replaceRoom(partyroomId);
        log.info("[VirtualCrewAdmin.replace] partyroomId={}", partyroomId.getId());
    }

    // ── bulk ──

    /**
     * 여러 룸에 같은 config 를 적용(체크박스 일괄). MANAGED 는 각각 reconcile(송팩 변경 시
     * replace — 방 전체 봇 교체), OFF 는 각각 drain.
     *
     * <p>per-room 트랜잭션 격리: {@code self} 프록시를 통해 룸마다 {@link #applyConfig} 의
     * {@code @Transactional} 경계를 독립적으로 적용한다. 한 룸의 reconcile/drain 예외가 다른 룸의
     * config 저장을 롤백하지 않도록, 각 룸의 실패를 try/catch 로 격리하고 배치를 계속 진행한다.
     */
    public void applyBulk(List<Long> partyroomIds, VirtualCrewStatus status, Integer targetCount,
                          Integer djBotCount, Long songPackId) {
        List<Long> failedIds = new ArrayList<>();
        for (Long id : partyroomIds) {
            try {
                self.applyConfig(new PartyroomId(id), status, targetCount, djBotCount, songPackId);
            } catch (Exception e) {
                log.warn("[VirtualCrewAdmin.applyBulk] ROOM_FAILED - partyroomId={}, reason={}",
                        id, e.getMessage());
                failedIds.add(id);
            }
        }
        log.info("[VirtualCrewAdmin.applyBulk] rooms={} status={} failed={}", partyroomIds.size(), status, failedIds.size());
        if (!failedIds.isEmpty()) {
            log.warn("[VirtualCrewAdmin.applyBulk] PARTIAL_FAILURE - failedPartyroomIds={}", failedIds);
        }
    }

    // ── live status ──

    @Transactional(readOnly = true)
    public LiveStatus liveStatus(PartyroomId partyroomId) {
        PartyroomVirtualCrewConfigData cfg = configRepository.findByPartyroomId(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(VirtualCrewException.CONFIG_NOT_FOUND));
        int currentBotDjCount = activeDjSnapshotService.snapshot(partyroomId).botCount();
        return new LiveStatus(cfg.getStatus(), cfg.getTargetCount(), cfg.getDjBotCount(),
                cfg.getSongPackId(), currentBotDjCount);
    }

    // ── helpers ──

    private PartyroomVirtualCrewConfigData loadOrCreate(PartyroomId partyroomId) {
        return configRepository.findByPartyroomId(partyroomId.getId())
                .orElseGet(() -> PartyroomVirtualCrewConfigData.create(partyroomId.getId()));
    }

    private void applyStatus(PartyroomVirtualCrewConfigData cfg, VirtualCrewStatus status,
                             Integer targetCount, Integer djBotCount, Long songPackId) {
        switch (status) {
            case MANAGED -> {
                if (targetCount == null || djBotCount == null) {
                    throw ExceptionCreator.create(VirtualCrewException.INVALID_CONFIG);
                }
                // djBotCount 는 목표 봇 수(targetCount) 를 넘을 수 없다.
                if (djBotCount > targetCount) {
                    throw ExceptionCreator.create(VirtualCrewException.INVALID_CONFIG);
                }
                // 송 팩이 지정되면, 각 봇에 분배될 트랙(룸 시간제한 통과분)이 djBotCount 이상이어야 한다.
                // countPlayableTracks 는 SongPackApplier.applyToBot 과 동일한 필터를 재사용한다.
                if (songPackId != null) {
                    PartyroomData room = partyroomQueryService.getPartyroomById(
                            new PartyroomId(cfg.getPartyroomId()));
                    int timeLimitMinutes = room.getPlaybackTimeLimit().getMinutes();
                    int playableTrackCount = songPackApplier.countPlayableTracks(songPackId, timeLimitMinutes);
                    if (djBotCount > playableTrackCount) {
                        throw ExceptionCreator.create(VirtualCrewException.DJ_COUNT_EXCEEDS_TRACKS);
                    }
                }
                cfg.applyManaged(targetCount, djBotCount, songPackId);
            }
            case OFF -> cfg.turnOff();
        }
    }

    /**
     * 룸의 가상 DJ live 상태.
     *
     * @param status            현재 config 상태(OFF/MANAGED)
     * @param targetCount       목표 봇 수
     * @param djBotCount           크루(DJ) 봇 수
     * @param songPackId        적용 송 팩 id (nullable)
     * @param currentBotDjCount 현재 활성 봇 DJ 수
     */
    public record LiveStatus(VirtualCrewStatus status, Integer targetCount, Integer djBotCount,
                             Long songPackId, int currentBotDjCount) {}

    /**
     * 봇 풀 전체 요약.
     *
     * @param total  전체 봇 수
     * @param idle   idle 봇 수
     * @param placed 파티룸별 배치 현황
     */
    public record PoolSummary(long total, long idle, List<Placement> placed) {
        /** 파티룸 1곳의 봇 배치 현황. */
        public record Placement(Long partyroomId, String partyroomTitle, long botCount) {}
    }
}
