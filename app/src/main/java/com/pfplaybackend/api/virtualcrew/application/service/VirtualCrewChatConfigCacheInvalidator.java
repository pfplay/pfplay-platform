package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.virtualcrew.application.event.VirtualCrewChatConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 가상 DJ 채팅/자가갱신 설정 변경 후 {@link SystemConfigCache} 스냅샷을 즉시 무효화한다.
 *
 * <p>AFTER_COMMIT phase 에서만 무효화한다 — 롤백된 트랜잭션이 캐시를 비우면 다음 read 가 옛 DB 값을
 * 다시 적재해 무의미하기 때문. 무효화는 per-instance 라 다른 인스턴스는 30s TTL 만료 시 따라온다
 * (분산 무효화 없음 — 어드민 토글의 stale window 는 허용 범위).
 */
@Component
@RequiredArgsConstructor
public class VirtualCrewChatConfigCacheInvalidator {

    private final SystemConfigCache systemConfigCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(VirtualCrewChatConfigChangedEvent event) {
        systemConfigCache.invalidate();
    }
}
