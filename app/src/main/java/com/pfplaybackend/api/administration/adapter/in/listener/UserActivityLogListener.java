package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent;
import com.pfplaybackend.api.common.config.AsyncConfig;
import com.pfplaybackend.api.common.log.MdcHelper;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.event.AdminCrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.CrewPenalizedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomCreatedEvent;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import com.pfplaybackend.api.user.domain.event.MemberTierChangedEvent;
import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
import com.pfplaybackend.api.user.domain.event.UserProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * user_activity_log audit timeline writer.
 *
 * 핵심 정책:
 * - `@TransactionalEventListener(AFTER_COMMIT)` — 비즈니스 TX commit 후 실행 (audit 실패 ≠ 비즈니스 실패).
 * - `@Async("userActivityLogExecutor")` — 핫패스 비동기화. spec §6 executor.
 * - drop-가능 — repository.save throw 시 ERROR 로그 + swallow. 비즈니스 흐름 안 막음.
 *
 * PR 8/9 PartyroomAdminActionListener의 sync atomic 패턴과 의도적 차별화.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.3, §7
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserActivityLogListener {

    private final UserActivityLogRepository repository;

    // === User/Member events ===

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(MemberRegisteredEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("provider", e.getProviderType().name());
        log(e.getUserId().getUid(), UserActivityEventType.SIGNED_UP, null,
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(UserAccountSignedInEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("provider", e.getProvider().name());
        meta.put("actor_type", e.getActorType().name());
        log(e.getUserAccountId(), UserActivityEventType.SIGNED_IN, null,
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(UserProfileChangedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("change_type", e.getChangeType().name());
        log(e.getUserId().getUid(), UserActivityEventType.PROFILE_UPDATED, null,
            JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(MemberTierChangedEvent e) {
        // Row 1: 대상 user 관점 TIER_CHANGED (insertion 먼저)
        Map<String, Object> tierMeta = new HashMap<>();
        tierMeta.put("old_tier", e.getOldTier().name());
        tierMeta.put("new_tier", e.getNewTier().name());
        tierMeta.put("by_administrator_id", e.getByAdministratorId());
        log(e.getUserAccountId(), UserActivityEventType.TIER_CHANGED, null,
            JsonMetadata.of(tierMeta), e.getOccurredAt());

        // Row 2: 대상 user 관점 ADMIN_ACTED_ON (log_id가 더 큼 → ORDER BY DESC에서 먼저 노출)
        Map<String, Object> actMeta = new HashMap<>();
        actMeta.put("action_type", "TIER_CHANGED");
        actMeta.put("by_administrator_id", e.getByAdministratorId());
        log(e.getUserAccountId(), UserActivityEventType.ADMIN_ACTED_ON, null,
            JsonMetadata.of(actMeta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(UserAccountWithdrawnEvent e) {
        // Row 1: WITHDREW
        Map<String, Object> wMeta = new HashMap<>();
        wMeta.put("by_administrator_id", e.getByAdministratorId());
        log(e.getUserAccountId(), UserActivityEventType.WITHDREW, null,
            JsonMetadata.of(wMeta), e.getOccurredAt());

        // Row 2: ADMIN_ACTED_ON
        Map<String, Object> actMeta = new HashMap<>();
        actMeta.put("action_type", "WITHDRAW");
        actMeta.put("by_administrator_id", e.getByAdministratorId());
        log(e.getUserAccountId(), UserActivityEventType.ADMIN_ACTED_ON, null,
            JsonMetadata.of(actMeta), e.getOccurredAt());
    }

    // === Party events ===

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(PartyroomCreatedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("stage_type", e.getStageType().name());
        log(e.getHostUserAccountId(), UserActivityEventType.PARTYROOM_CREATED,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(CrewAccessedEvent e) {
        try (var ignored = MdcHelper.scope("partyroomId", e.getPartyroomId().getId())) {
            UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
                    ? UserActivityEventType.PARTYROOM_ENTERED
                    : UserActivityEventType.PARTYROOM_EXITED;
            log.info("[on.CrewAccessedEvent] type={} userId={} partyroomId={}",
                    type, e.getUserId().getUid(), e.getPartyroomId().getId());
            // metadata 단순화 — CrewAccessedEvent에 stage_type/duration_sec 부재
            // (spec §4.7.2의 metadata 키는 예시; future evolution으로 보강 가능).
            // JsonMetadata.empty() — converter가 빈 map을 SQL NULL로 직렬화.
            log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
                JsonMetadata.empty(), e.getOccurredAt());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(CrewPenalizedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("penalty_type", e.getPenaltyType().name());
        meta.put("by", "CREW");
        log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
    public void on(AdminCrewPenalizedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("penalty_type", e.getPenaltyType().name());
        meta.put("by", "ADMIN");
        meta.put("by_administrator_id", e.getAdministratorId());
        log(e.getPunishedUserAccountId(), UserActivityEventType.PENALIZED_IN_PARTYROOM,
            e.getPartyroomId().getId(), JsonMetadata.of(meta), e.getOccurredAt());
    }

    /**
     * 공통 INSERT 헬퍼 — drop-가능 정책 (try/catch swallow).
     * `@Async` thread context이므로 throw해도 publisher에 전파 안 됨 — 명시적 swallow.
     * `Throwable`이 아닌 `Exception`만 잡아 `Error`(OOM 등)는 그대로 전파한다.
     */
    private void log(Long userAccountId, UserActivityEventType type,
                     Long partyroomId, JsonMetadata meta, LocalDateTime occurredAt) {
        try {
            repository.save(UserActivityLogData.of(
                    userAccountId, type, partyroomId, meta, occurredAt));
        } catch (Exception ex) {
            log.error("[UAL] failed to insert: type={}, userAccountId={}, partyroomId={}",
                    type, userAccountId, partyroomId, ex);
        }
    }
}
