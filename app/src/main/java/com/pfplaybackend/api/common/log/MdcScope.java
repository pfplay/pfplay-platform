package com.pfplaybackend.api.common.log;

/**
 * try-with-resources 호환 MDC scope. {@code AutoCloseable.close() throws Exception} 의
 * checked 시그니처를 unchecked {@code void close()} 로 override — 호출자가 별도
 * try-catch 없이 자연스럽게 try-with-resources 사용 가능.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.5.
 */
public interface MdcScope extends AutoCloseable {
    @Override
    void close();
}
