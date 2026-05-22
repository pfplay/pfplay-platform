package com.pfplaybackend.api.common.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * HTTP 요청 상관관계 인터셉터.
 *
 * <p>들어오는 {@code X-Request-Id} 헤더 (sanitize 후) 또는 자동 생성 8자 id 를
 * MDC {@code requestId} 키로 푸시. 인증된 사용자의 uid 가 SecurityContext 에 있으면
 * {@code userId} MDC 도 함께 설정. afterCompletion 에서 둘 다 remove.
 *
 * <p>{@link #current()} 는 backward compat — MDC.get("requestId") 위임. A1
 * (`DjCommandService` 등) critical-path 로그가 사용.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.2.
 * Phase A6 (platform#210) 의 ThreadLocal 단계에서 MDC 격상 — Phase B2.
 *
 * <p>SecurityContext 가 preHandle 시점에 populated: Spring Security Filter Chain 이
 * DispatcherServlet 보다 먼저 실행되어 인증된 요청은 SecurityContextHolder 가 이미 채워짐.
 *
 * <p>async dispatch (DeferredResult/Callable) 미해소 — spec §10.4 참조.
 */
@Component
public class RequestIdInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "requestId";

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER_ID = "userId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = extractOrGenerate(request);

        MDC.put(MDC_REQUEST_ID, requestId);
        // userId: SecurityContext 의 principal name 에서 추출 (A6 단계엔 미적용 — 본 단계서 시도, null safe)
        String userId = extractUserId();
        if (userId != null) MDC.put(MDC_USER_ID, userId);

        request.setAttribute(REQUEST_ID_ATTR, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_USER_ID);
    }

    /**
     * Backward compat — A1 critical-path 로그 호출자 (DjCommandService 등) 가 사용.
     * MDC.get 위임. HTTP 컨텍스트 밖에서는 null.
     */
    public static String current() {
        return MDC.get(MDC_REQUEST_ID);
    }

    private String extractOrGenerate(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        // 신뢰 경계: 클라이언트 제어값. 제어문자 제거 + 길이 상한.
        requestId = requestId.replaceAll("\\p{Cntrl}", "");
        if (requestId.length() > 64) requestId = requestId.substring(0, 64);
        if (requestId.isBlank()) return UUID.randomUUID().toString().substring(0, 8);
        return requestId;
    }

    /**
     * SecurityContext 에서 인증된 사용자의 uid 추출. 비인증/익명 = null.
     */
    private String extractUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            String name = auth.getName();
            if (name == null || name.isBlank() || "anonymousUser".equals(name)) return null;
            return name;
        } catch (Exception ignored) {
            return null;
        }
    }
}
