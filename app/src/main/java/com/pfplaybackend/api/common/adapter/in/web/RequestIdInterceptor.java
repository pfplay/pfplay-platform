package com.pfplaybackend.api.common.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * HTTP 요청 상관관계(correlation) 인터셉터.
 *
 * 들어오는 {@code X-Request-Id} 헤더가 있으면 그대로 사용하고, 없거나 공백이면
 * 8자 짧은 id 를 생성한다. 값을 (1) request attribute({@value #REQUEST_ID_ATTR}),
 * (2) 응답 헤더({@value #REQUEST_ID_HEADER}, 디버깅용 에코), (3) ThreadLocal
 * ({@link #current()}) 세 곳에 노출한다.
 *
 * {@link #current()} 는 A1(핫패스 로그에 requestId 부착) 이 소비할 진입점이다.
 * HTTP 컨텍스트가 아니면 null 을 반환하므로 호출자는 반드시 null-safe 해야 한다.
 *
 * 비동기(async DeferredResult/Callable) dispatch 는 {@code afterConcurrentHandlingStarted}
 * 경로라 {@link #afterCompletion} 이 즉시 호출되지 않아 컨테이너 스레드에 stale requestId 가
 * 남을 수 있다. 다만 다음 요청 {@link #preHandle} 이 무조건 덮어쓰므로 누적되지 않고
 * bounded 이며, {@code current()} 소비자는 off-request 시 best-effort 전제다.
 * Phase B MDC 전환 시 해소된다.
 *
 * Spec: 옵저버빌리티 A6 (platform#210, Cluster C / C/T1-1)
 */
@Component
public class RequestIdInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "requestId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        } else {
            // 신뢰 경계: 클라이언트 제어값. 제어문자 제거(log-forging/헤더 인젝션 방어) + 길이 상한(log 증폭 방어).
            requestId = requestId.replaceAll("\\p{Cntrl}", "");
            if (requestId.length() > 64) {
                requestId = requestId.substring(0, 64);
            }
            if (requestId.isBlank()) { // 제거 후 공백만 남으면 생성
                requestId = UUID.randomUUID().toString().substring(0, 8);
            }
        }
        request.setAttribute(REQUEST_ID_ATTR, requestId);
        CURRENT.set(requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CURRENT.remove();
    }

    /** 현재 스레드의 requestId. 비-HTTP 컨텍스트면 null — 호출자는 null-safe 해야 한다. */
    public static String current() {
        return CURRENT.get();
    }
}
