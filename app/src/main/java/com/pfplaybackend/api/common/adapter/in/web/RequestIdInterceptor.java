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
