package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.config.AsyncConfig;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
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
    public void on(UserProfileChangedEvent e) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("change_type", e.getChangeType().name());
        log(e.getUserId().getUid(), UserActivityEventType.PROFILE_UPDATED, null,
            JsonMetadata.of(meta), e.getOccurredAt());
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
