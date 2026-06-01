package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.application.service.FlapGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * {@link FlapGuard} 단위 테스트 — 제어된 시각({@link Instant})으로 debounce/dwell 경계를 검증한다.
 *
 * <p>FlapGuard 는 제거(removal)만 게이팅한다(추가는 즉시). 시간은 호출자가 {@link Instant} 로
 * 주입하므로 별도 Clock mock 없이 경계 진위만 검사한다.
 */
@ExtendWith(MockitoExtension.class)
class FlapGuardTest {

    private static final long DEBOUNCE_MS = 5_000;
    private static final long DWELL_MS = 10_000;

    @Mock
    SystemConfigCache configCache;

    FlapGuard flapGuard;

    private final PartyroomId room = new PartyroomId(1L);
    private final Instant t0 = Instant.parse("2026-06-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(configCache.getBotYieldDebounceMs()).thenReturn((int) DEBOUNCE_MS);
        lenient().when(configCache.getBotMinDwellMs()).thenReturn((int) DWELL_MS);
        flapGuard = new FlapGuard(configCache);
    }

    @Test
    @DisplayName("shouldRemove — 첫 호출은 의도만 기록하고 false")
    void shouldRemove_first_call_records_intent_and_returns_false() {
        assertThat(flapGuard.shouldRemove(room, t0)).isFalse();
    }

    @Test
    @DisplayName("shouldRemove — debounce 경과 전 false, 경과 후 true")
    void shouldRemove_debounce_boundary() {
        assertThat(flapGuard.shouldRemove(room, t0)).isFalse();
        // 4.999s — 아직 미경과
        assertThat(flapGuard.shouldRemove(room, t0.plusMillis(DEBOUNCE_MS - 1))).isFalse();
        // 정확히 5s — 경과
        assertThat(flapGuard.shouldRemove(room, t0.plusMillis(DEBOUNCE_MS))).isTrue();
        // 그 이후도 true
        assertThat(flapGuard.shouldRemove(room, t0.plusMillis(DEBOUNCE_MS + 1000))).isTrue();
    }

    @Test
    @DisplayName("clearRemovalIntent — 의도 해제 후 다시 호출하면 debounce 가 재시작된다")
    void clearRemovalIntent_resets_debounce() {
        assertThat(flapGuard.shouldRemove(room, t0)).isFalse();
        assertThat(flapGuard.shouldRemove(room, t0.plusMillis(DEBOUNCE_MS))).isTrue();

        // 제거 불필요 판정 → 의도 해제
        flapGuard.clearRemovalIntent(room);

        // 이후 다시 제거 의도가 생기면 debounce 가 처음부터 다시 시작
        Instant t1 = t0.plusMillis(20_000);
        assertThat(flapGuard.shouldRemove(room, t1)).isFalse();
        assertThat(flapGuard.shouldRemove(room, t1.plusMillis(DEBOUNCE_MS - 1))).isFalse();
        assertThat(flapGuard.shouldRemove(room, t1.plusMillis(DEBOUNCE_MS))).isTrue();
    }

    @Test
    @DisplayName("canRemoveBot — dwell 미경과면 false, 경과면 true")
    void canRemoveBot_dwell_boundary() {
        UserId bot = new UserId(7001L);
        flapGuard.markAdded(bot, t0);

        // dwell 미경과
        assertThat(flapGuard.canRemoveBot(bot, t0.plusMillis(DWELL_MS - 1))).isFalse();
        // 정확히 dwell 경과
        assertThat(flapGuard.canRemoveBot(bot, t0.plusMillis(DWELL_MS))).isTrue();
    }

    @Test
    @DisplayName("canRemoveBot — markAdded 기록이 없는 봇은 제거 가능(보수적 fail-open)")
    void canRemoveBot_unknown_bot_is_removable() {
        UserId unknown = new UserId(9999L);
        assertThat(flapGuard.canRemoveBot(unknown, t0)).isTrue();
    }
}
