package com.pfplaybackend.api.common.config.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * F#220 — 인증 진입점({@link AuthEntryPoints})에서는 토큰 검증을 통째로 생략하는
 * thin {@link BearerTokenResolver} 데코레이터.
 * <p>
 * 진입점 매칭 시 {@code null} 반환 → {@code BearerTokenAuthenticationFilter}가
 * 인증을 시도하지 않음 → 인가 계층의 {@code permitAll}이 정상 적용 →
 * stale/무효 {@code SharedSessionToken} 쿠키로 인한 진입점 401이 사라진다.
 * <p>
 * 그 외 경로는 {@link CookieBearerTokenResolver}(쿠키 이름 picker)에 그대로 위임 —
 * 일반 인증 경로는 무회귀. 내부 resolver는 open/closed 원칙대로 byte 변경 없음.
 */
@RequiredArgsConstructor
public class AuthEntryPointSkippingBearerTokenResolver implements BearerTokenResolver {

    private final RequestMatcher authEntryPoints;
    private final BearerTokenResolver delegate;

    @Override
    public String resolve(HttpServletRequest request) {
        if (authEntryPoints.matches(request)) {
            return null;
        }
        return delegate.resolve(request);
    }
}
