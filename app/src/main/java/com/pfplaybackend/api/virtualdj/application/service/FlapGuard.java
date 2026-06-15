package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 봇 DJ thrash 방지(anti-flap) 가드 — <b>제거(removal)만</b> 게이팅한다. 추가는 음악 연속성을 위해
 * 즉시 수행되므로 이 가드를 거치지 않는다.
 *
 * <p>두 축의 방어:
 * <ul>
 *   <li><b>yield debounce</b>: 룸 단위 "제거하고 싶다"는 의도가 {@code bot_yield_debounce_ms} 이상
 *       지속돼야 실제 제거를 허용. 사람 DJ 가 잠깐 들어왔다 나가는 깜빡임에 봇을 즉시 내보내지 않는다.</li>
 *   <li><b>min dwell</b>: 방금 투입된 봇은 {@code bot_min_dwell_ms} 이내 제거 보호. 갓 들어온 봇이
 *       바로 다시 나가는 churn 방지.</li>
 * </ul>
 *
 * <p><b>상태는 in-memory(ConcurrentHashMap)</b>다. 단일 인스턴스 백엔드 가정에서 정확하며,
 * 다중 인스턴스로 확장하면 인스턴스별 타이머가 분리된다(그 경우 reconcile 직렬화는 분산락이
 * 담당하므로 안전성은 유지되나 debounce 가 인스턴스별로 독립 측정됨 — 현 단계 범위 밖).
 *
 * <p>시각은 호출자가 DI 된 {@code Clock} 으로 산출한 {@link Instant} 를 주입한다(테스트 결정성).
 */
@Component
@RequiredArgsConstructor
public class FlapGuard {

    private final SystemConfigCache configCache;

    /** 룸별 "제거하고 싶다" 의도 시작 시각. */
    private final ConcurrentHashMap<PartyroomId, Instant> removalIntentStartedAt = new ConcurrentHashMap<>();
    /** 봇별 투입 시각. */
    private final ConcurrentHashMap<UserId, Instant> botAddedAt = new ConcurrentHashMap<>();

    /**
     * 봇 제거 의도가 debounce 를 넘겼는지. 첫 호출은 의도 시각만 기록하고 {@code false}; 이후
     * {@code now - intentStart >= bot_yield_debounce_ms} 가 되면 {@code true}.
     */
    public boolean shouldRemove(PartyroomId roomId, Instant now) {
        Instant start = removalIntentStartedAt.putIfAbsent(roomId, now);
        if (start == null) {
            // 이번이 첫 의도 — 기록만 하고 보류.
            return false;
        }
        long elapsed = now.toEpochMilli() - start.toEpochMilli();
        return elapsed >= configCache.getBotYieldDebounceMs();
    }

    /**
     * 제거가 더 이상 필요 없을 때(botCount &lt;= desired) 룸의 제거 의도를 해제한다. 다음에 다시
     * 제거 의도가 생기면 debounce 가 처음부터 다시 측정된다.
     */
    public void clearRemovalIntent(PartyroomId roomId) {
        removalIntentStartedAt.remove(roomId);
    }

    /**
     * 봇이 투입 후 최소 체류시간({@code bot_min_dwell_ms})을 채웠는지. {@link #markAdded} 기록이 없는
     * 봇(부팅 전 투입분 등)은 보수적으로 제거 가능({@code true}) 처리한다.
     */
    public boolean canRemoveBot(UserId botUserId, Instant now) {
        Instant added = botAddedAt.get(botUserId);
        if (added == null) {
            return true;
        }
        long dwell = now.toEpochMilli() - added.toEpochMilli();
        return dwell >= configCache.getBotMinDwellMs();
    }

    /** 봇 투입 시각 기록 — 오케스트레이터가 봇을 추가한 직후 호출한다. */
    public void markAdded(UserId botUserId, Instant now) {
        botAddedAt.put(botUserId, now);
    }

    /** 봇 투입 기록 제거 — 봇이 제거되면 호출(누수 방지). */
    public void clearAdded(UserId botUserId) {
        botAddedAt.remove(botUserId);
    }
}
