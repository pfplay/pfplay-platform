package com.pfplaybackend.api.common.log;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Producer thread 의 MDC context 를 worker thread 로 복사.
 *
 * <p>Best-effort restore: finally 의 {@code prev} 는 worker 의 *기존* MDC (보통
 * clean pool thread 라 null). pool thread 에 stale MDC 가 leftover 라면 그 값을
 * 복원 — 그건 별도 코드 path 의 leak 이고, 본 decorator 는 *그 leak 을 보존* 한다
 * (decorator 가 leak fix 책임 아님). 정상 case 에서는 prev == null 이라
 * {@code MDC.clear()} = 깨끗한 thread 복원.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.4.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            try {
                if (context != null) MDC.setContextMap(context);
                else MDC.clear();
                runnable.run();
            } finally {
                if (prev != null) MDC.setContextMap(prev);
                else MDC.clear();
            }
        };
    }
}
