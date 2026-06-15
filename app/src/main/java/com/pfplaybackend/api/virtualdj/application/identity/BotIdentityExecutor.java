package com.pfplaybackend.api.virtualdj.application.identity;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import org.springframework.stereotype.Component;

/**
 * 봇(가상 사용자) 신원으로 도메인 명령을 실행하기 위한 임퍼소네이션 유틸 (= path A).
 *
 * <p>봇은 실제 {@code user_account}(is_dummy=true)이며, 오케스트레이터는 봇의
 * {@link AuthContext} 를 {@link ThreadLocalContext} 에 심은 뒤 실유저가 쓰는 동일한 public
 * 명령 서비스({@code tryEnter}/{@code enqueueDj}/{@code exit} 등)를 그대로 호출한다. 이로써
 * 기존 가드·캐스케이드를 한 줄도 우회하지 않는다.
 *
 * <p>봇의 권한 계층은 일반 정식 회원과 동일한 {@link AuthorityTier#FM} 이다 — 봇은
 * {@code AdminUserService.createVirtualMember} 와 같은 경로로 만들어진 FM 멤버이기 때문이다.
 */
@Component
public class BotIdentityExecutor {

    private static final AuthorityTier BOT_AUTHORITY_TIER = AuthorityTier.FM;

    /**
     * {@code botUserId} 의 {@link AuthContext} 를 현재 스레드에 설정하고 {@code action} 을
     * 실행한다. 실행 전후로 ThreadLocal 을 항상 정리한다 — 이전 컨텍스트가 있었으면 복원하고,
     * 없었으면 비운다. {@code action} 이 예외를 던지면 그 예외를 전파하되 정리는 반드시 수행한다.
     */
    public void runAs(UserId botUserId, Runnable action) {
        // getAuthContext() 는 컨텍스트가 없으면 throw 하므로 raw getContext() 로 이전 값을 보존한다.
        Object previous = ThreadLocalContext.getContext();
        try {
            ThreadLocalContext.setContext(buildBotAuthContext(botUserId));
            action.run();
        } finally {
            if (previous != null) {
                ThreadLocalContext.setContext(previous);
            } else {
                ThreadLocalContext.clearContext();
            }
        }
    }

    private AuthContext buildBotAuthContext(UserId botUserId) {
        return new AuthContext(botUserId, BOT_AUTHORITY_TIER);
    }
}
