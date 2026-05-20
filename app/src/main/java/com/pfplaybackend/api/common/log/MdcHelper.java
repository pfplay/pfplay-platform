package com.pfplaybackend.api.common.log;

import org.slf4j.MDC;

/**
 * MDC 키 스코프 진입/복원 helper.
 *
 * <p>{@link MdcScope} 가 unchecked {@code close()} 라 caller 는 try-with-resources 만 사용:
 * <pre>{@code
 * try (var ignored = MdcHelper.scope("partyroomId", id)) {
 *     log.info("...");  // partyroomId 가 jsonPayload 에 emit
 * }
 * }</pre>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.5.
 */
public final class MdcHelper {

    private static final MdcScope NOOP = () -> {};

    private MdcHelper() {}

    /**
     * MDC[key] 에 value 설정 + scope close 시 이전 값 복원 (이전 값 없으면 remove).
     * value 가 null 이면 no-op MdcScope 반환 (MDC 미수정).
     */
    public static MdcScope scope(String key, Object value) {
        if (value == null) return NOOP;

        String prev = MDC.get(key);
        MDC.put(key, String.valueOf(value));
        return () -> {
            if (prev != null) MDC.put(key, prev);
            else MDC.remove(key);
        };
    }
}
