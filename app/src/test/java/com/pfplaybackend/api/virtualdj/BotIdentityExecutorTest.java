package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.identity.BotIdentityExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotIdentityExecutorTest {

    private final BotIdentityExecutor executor = new BotIdentityExecutor();

    @AfterEach
    void cleanUp() {
        // Defensive: never let a leaked context bleed into the next test.
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("봇 신원으로 실행하고 종료 후 정리한다")
    void 봇_신원으로_실행하고_종료후_정리한다() {
        UserId bot = new UserId(1_000_000_001L);
        Long[] inside = new Long[1];

        executor.runAs(bot, () ->
                inside[0] = ThreadLocalContext.getAuthContext().getUserId().getUid());

        assertThat(inside[0]).isEqualTo(1_000_000_001L);
        // 실행이 끝나면 ThreadLocal 은 비어 있어야 한다.
        assertThatThrownBy(ThreadLocalContext::getAuthContext)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기존 컨텍스트가 있으면 종료 후 복원한다")
    void 기존_컨텍스트가_있으면_복원한다() {
        AuthContext previous = new AuthContext(new UserId(42L), null);
        ThreadLocalContext.setContext(previous);

        UserId bot = new UserId(1_000_000_002L);
        Long[] inside = new Long[1];

        executor.runAs(bot, () ->
                inside[0] = ThreadLocalContext.getAuthContext().getUserId().getUid());

        // 실행 중에는 봇이 컨텍스트의 주인.
        assertThat(inside[0]).isEqualTo(1_000_000_002L);
        // 종료 후에는 이전 컨텍스트가 그대로 복원되어야 한다.
        assertThat(ThreadLocalContext.getAuthContext()).isSameAs(previous);
    }

    @Test
    @DisplayName("action 이 예외를 던져도 예외는 전파되고 ThreadLocal 은 정리된다")
    void 예외가_나도_ThreadLocal_정리된다() {
        UserId bot = new UserId(1L);

        assertThatThrownBy(() -> executor.runAs(bot, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThatThrownBy(ThreadLocalContext::getAuthContext)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("action 예외 시에도 이전 컨텍스트는 복원된다")
    void 예외가_나도_이전_컨텍스트_복원() {
        AuthContext previous = new AuthContext(new UserId(7L), null);
        ThreadLocalContext.setContext(previous);

        assertThatThrownBy(() -> executor.runAs(new UserId(99L), () -> {
            throw new RuntimeException("x");
        })).isInstanceOf(RuntimeException.class);

        assertThat(ThreadLocalContext.getAuthContext()).isSameAs(previous);
    }
}
