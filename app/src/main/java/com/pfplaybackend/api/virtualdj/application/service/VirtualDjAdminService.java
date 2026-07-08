package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.dto.PoolPlacementRow;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 어드민 가상 DJ 운영 서비스 — config 적용/drain + 단건/일괄 reconcile + live status 조회.
 *
 * <p>config 전환은 도메인 메서드({@link PartyroomVirtualDjConfigData#applyManaged}/{@code turnOff})를
 * 통해서만 수행하고, 봇 투입/제거는 모두 {@link VirtualDjOrchestrator}(봇 신원 path A)에 위임한다.
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
public class VirtualDjAdminService {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final VirtualDjOrchestrator orchestrator;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final VirtualUserPoolService poolService;
    private final BotPoolQueryRepository botPoolQueryRepository;

    /**
     * applyBulk 의 per-room 격리를 위한 self-proxy 참조.
     * {@code @Lazy} 로 순환 의존성을 방지하고, setter 주입으로 Spring AOP 프록시를 통해
     * {@link #applyConfig} 의 {@code @Transactional} 경계가 룸마다 독립적으로 적용되도록 한다.
     */
    @Setter(onMethod_ = {@Autowired, @Lazy})
    private VirtualDjAdminService self;

    // ── pool ──

    public void provisionPool(int count) {
        poolService.provision(count);
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

    /** 단일 룸 config 적용 후 MANAGED 면 reconcile, OFF 면 drain 을 같은 트랜잭션에서 트리거. */
    @Transactional
    public void applyConfig(PartyroomId partyroomId, VirtualDjStatus status, Integer targetCount,
                            Integer djCount, Long songPackId) {
        PartyroomVirtualDjConfigData cfg = loadOrCreate(partyroomId);
        applyStatus(cfg, status, targetCount, djCount, songPackId);
        // saveAndFlush: reconcile/drain 의 봇 명령 경로가 영속성 컨텍스트를 clear 할 수 있어,
        // config 변경을 즉시 DB 에 확정한 뒤 reconcile(자기 config 재조회)/drain 을 트리거한다.
        configRepository.saveAndFlush(cfg);
        log.info("[VirtualDjAdmin.applyConfig] partyroomId={} status={} target={} djCount={} songPackId={}",
                partyroomId.getId(), status, targetCount, djCount, songPackId);

        if (status == VirtualDjStatus.MANAGED) {
            orchestrator.reconcileRoom(partyroomId);
        } else if (status == VirtualDjStatus.OFF) {
            orchestrator.drainRoom(partyroomId);
        }
    }

    /** 룸 비우기 — config OFF + 모든 봇 제거(drain, path A) + FlapGuard 정리. */
    @Transactional
    public void drain(PartyroomId partyroomId) {
        PartyroomVirtualDjConfigData cfg = loadOrCreate(partyroomId);
        cfg.turnOff();
        // OFF 를 즉시 flush — drainRoom 의 봇 exit 경로가 영속성 컨텍스트를 clear 할 수 있어,
        // 미flush 된 dirty 변경이 detach 되어 유실되는 것을 방지한다(DB 에 OFF 확정).
        configRepository.saveAndFlush(cfg);
        orchestrator.drainRoom(partyroomId);
        log.info("[VirtualDjAdmin.drain] partyroomId={}", partyroomId.getId());
    }

    // ── bulk ──

    /**
     * 여러 룸에 같은 config 를 적용(체크박스 일괄). MANAGED 는 각각 reconcile, OFF 는 각각 drain.
     *
     * <p>per-room 트랜잭션 격리: {@code self} 프록시를 통해 룸마다 {@link #applyConfig} 의
     * {@code @Transactional} 경계를 독립적으로 적용한다. 한 룸의 reconcile/drain 예외가 다른 룸의
     * config 저장을 롤백하지 않도록, 각 룸의 실패를 try/catch 로 격리하고 배치를 계속 진행한다.
     */
    public void applyBulk(List<Long> partyroomIds, VirtualDjStatus status, Integer targetCount,
                          Integer djCount, Long songPackId) {
        List<Long> failedIds = new ArrayList<>();
        for (Long id : partyroomIds) {
            try {
                self.applyConfig(new PartyroomId(id), status, targetCount, djCount, songPackId);
            } catch (Exception e) {
                log.warn("[VirtualDjAdmin.applyBulk] ROOM_FAILED - partyroomId={}, reason={}",
                        id, e.getMessage());
                failedIds.add(id);
            }
        }
        log.info("[VirtualDjAdmin.applyBulk] rooms={} status={} failed={}", partyroomIds.size(), status, failedIds.size());
        if (!failedIds.isEmpty()) {
            log.warn("[VirtualDjAdmin.applyBulk] PARTIAL_FAILURE - failedPartyroomIds={}", failedIds);
        }
    }

    // ── live status ──

    @Transactional(readOnly = true)
    public LiveStatus liveStatus(PartyroomId partyroomId) {
        PartyroomVirtualDjConfigData cfg = configRepository.findByPartyroomId(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.CONFIG_NOT_FOUND));
        int currentBotDjCount = activeDjSnapshotService.snapshot(partyroomId).botCount();
        return new LiveStatus(cfg.getStatus(), cfg.getTargetCount(), cfg.getDjCount(),
                cfg.getSongPackId(), currentBotDjCount);
    }

    // ── helpers ──

    private PartyroomVirtualDjConfigData loadOrCreate(PartyroomId partyroomId) {
        return configRepository.findByPartyroomId(partyroomId.getId())
                .orElseGet(() -> PartyroomVirtualDjConfigData.create(partyroomId.getId()));
    }

    private void applyStatus(PartyroomVirtualDjConfigData cfg, VirtualDjStatus status,
                             Integer targetCount, Integer djCount, Long songPackId) {
        switch (status) {
            case MANAGED -> {
                if (targetCount == null || djCount == null) {
                    throw ExceptionCreator.create(VirtualDjException.INVALID_CONFIG);
                }
                cfg.applyManaged(targetCount, djCount, songPackId);
            }
            case OFF -> cfg.turnOff();
        }
    }

    /**
     * 룸의 가상 DJ live 상태.
     *
     * @param status            현재 config 상태(OFF/MANAGED)
     * @param targetCount       목표 봇 수
     * @param djCount           크루(DJ) 봇 수
     * @param songPackId        적용 송 팩 id (nullable)
     * @param currentBotDjCount 현재 활성 봇 DJ 수
     */
    public record LiveStatus(VirtualDjStatus status, Integer targetCount, Integer djCount,
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
