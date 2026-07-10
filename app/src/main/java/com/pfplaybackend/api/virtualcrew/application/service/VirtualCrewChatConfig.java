package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가상 DJ P3 채팅 런타임 설정 읽기 래퍼 (Chunk 3).
 *
 * <p>{@link SystemConfigCache} 의 fail-open readInt/readBoolean 에 위임한다 — 키 누락/오타/빈 값은
 * 아래 DEFAULT 로 폴백된다. {@link #isEnabled()} 는 전역 kill switch이며 <b>기본 잠금(false)</b>이다:
 * 설정 행이 사라지거나 캐시가 못 읽어도 봇 채팅은 켜지지 않는다(fail-closed). 외부 LLM 키 +
 * 제품 승인 후 행을 true 로 바꿔 명시 활성화한다(V27 시드 참조).
 *
 * <p>확률은 정수 퍼센트(0-100)다 — SystemConfigCache 에 readDouble 이 없기 때문.
 * max.inflight 키는 의도적으로 없음(동시성은 후속 chunk 의 단일 게이트 키 TTL 로 처리).
 */
@Component
@RequiredArgsConstructor
public class VirtualCrewChatConfig {

    static final boolean DEFAULT_ENABLED = false;   // fail-closed: 기본 잠금
    static final int DEFAULT_PROBABILITY_PERCENT = 12;
    static final int DEFAULT_COOLDOWN_SECONDS = 30;
    static final int DEFAULT_CONTEXT_SIZE = 20;
    static final int DEFAULT_OUTPUT_MAX_TOKENS = 256;

    private final SystemConfigCache cache;

    /** 전역 kill switch. */
    public boolean isEnabled() {
        return cache.readBoolean(ConfigKey.VCREW_CHAT_ENABLED, DEFAULT_ENABLED);
    }

    /** 사람 메시지당 봇 응답 시도 확률(정수 퍼센트 0-100). */
    public int probabilityPercent() {
        return cache.readInt(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, DEFAULT_PROBABILITY_PERCENT);
    }

    /** 방별 봇 응답 최소 간격(초). 게이트 SETNX 키 TTL 겸용. */
    public int cooldownSeconds() {
        return cache.readInt(ConfigKey.VCREW_CHAT_ROOM_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS);
    }

    /** LLM 주입 최근 사람 메시지 수. */
    public int contextSize() {
        return cache.readInt(ConfigKey.VCREW_CHAT_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE);
    }

    /** 봇 응답 최대 토큰. */
    public int outputMaxTokens() {
        return cache.readInt(ConfigKey.VCREW_CHAT_OUTPUT_MAX_TOKENS, DEFAULT_OUTPUT_MAX_TOKENS);
    }
}
