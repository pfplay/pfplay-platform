package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import com.pfplaybackend.api.virtualcrew.application.dto.ChatConfigView;
import com.pfplaybackend.api.virtualcrew.application.event.VirtualCrewChatConfigChangedEvent;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 가상 DJ 채팅/자가갱신 런타임 설정의 어드민 read/update 서비스 (P3 패널, Chunk 1).
 *
 * <p>이 서비스는 in-app 최초의 {@code system_config} writer 다 — 그동안 {@code vcrew.chat.*} 키는
 * SQL 로만 편집했다. {@code findByConfigKey} 가 있으면 {@link SystemConfigData#updateValue} 로 in-place
 * 갱신, 없으면 {@link SystemConfigData#create} 로 신규 행을 upsert(PK assigned)한다.
 *
 * <p>커밋 후 캐시 무효화는 {@link VirtualCrewChatConfigChangedEvent} → AFTER_COMMIT 리스너
 * ({@code VirtualCrewChatConfigCacheInvalidator}) 가 담당한다. read 는 fail-open 파싱(누락/오타/빈 값 →
 * 코드 DEFAULT)으로 어떤 더러운 행도 어드민 화면을 깨지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class VirtualCrewChatConfigAdminService {

    static final boolean DEFAULT_CHAT_ENABLED = false;
    static final boolean DEFAULT_SELF_UPDATE_ENABLED = false;
    static final int DEFAULT_PROBABILITY_PERCENT = 12;
    static final int DEFAULT_COOLDOWN_SECONDS = 30;
    static final int DEFAULT_CONTEXT_SIZE = 20;
    static final int DEFAULT_OUTPUT_MAX_TOKENS = 256;

    private static final String DESC_CHAT_ENABLED = "P3 봇 채팅 전역 kill switch(기본 잠금 — 키+승인 후 활성화)";
    private static final String DESC_SELF_UPDATE_ENABLED = "P3-B 봇 플레이리스트 자가갱신 전역 토글(기본 잠금 — 구현 후 활성화)";
    private static final String DESC_PROBABILITY = "사람 메시지당 봇 응답 시도 확률(%)";
    private static final String DESC_COOLDOWN = "방별 봇 응답 최소 간격(초). 게이트 SETNX 키 TTL 겸용";
    private static final String DESC_CONTEXT_SIZE = "LLM 주입 최근 사람 메시지 수";
    private static final String DESC_MAX_TOKENS = "봇 응답 최대 토큰";

    private final SystemConfigRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminContext adminContext;

    @Transactional(readOnly = true)
    public ChatConfigView read() {
        return new ChatConfigView(
                readBool(ConfigKey.VCREW_CHAT_ENABLED, DEFAULT_CHAT_ENABLED),
                readBool(ConfigKey.VCREW_PLAYLIST_SELF_UPDATE_ENABLED, DEFAULT_SELF_UPDATE_ENABLED),
                readInt(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, DEFAULT_PROBABILITY_PERCENT),
                readInt(ConfigKey.VCREW_CHAT_ROOM_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS),
                readInt(ConfigKey.VCREW_CHAT_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE),
                readInt(ConfigKey.VCREW_CHAT_OUTPUT_MAX_TOKENS, DEFAULT_OUTPUT_MAX_TOKENS));
    }

    @Transactional
    public void update(boolean chatEnabled, boolean selfUpdateEnabled, int probabilityPercent,
                       int cooldownSeconds, int contextSize, int outputMaxTokens) {
        // 검증 우선 — 어떤 행도 쓰기 전에 전체 입력을 단언한다(부분 갱신 방지).
        if (probabilityPercent < 0 || probabilityPercent > 100
                || cooldownSeconds < 1 || contextSize < 1 || outputMaxTokens < 1) {
            throw ExceptionCreator.create(VirtualCrewException.CHAT_CONFIG_INVALID);
        }

        Long adminId = adminContext.currentAdministratorId();

        upsert(ConfigKey.VCREW_CHAT_ENABLED, Boolean.toString(chatEnabled), DESC_CHAT_ENABLED, adminId);
        upsert(ConfigKey.VCREW_PLAYLIST_SELF_UPDATE_ENABLED, Boolean.toString(selfUpdateEnabled), DESC_SELF_UPDATE_ENABLED, adminId);
        upsert(ConfigKey.VCREW_CHAT_TRIGGER_PROBABILITY, Integer.toString(probabilityPercent), DESC_PROBABILITY, adminId);
        upsert(ConfigKey.VCREW_CHAT_ROOM_COOLDOWN_SECONDS, Integer.toString(cooldownSeconds), DESC_COOLDOWN, adminId);
        upsert(ConfigKey.VCREW_CHAT_CONTEXT_SIZE, Integer.toString(contextSize), DESC_CONTEXT_SIZE, adminId);
        upsert(ConfigKey.VCREW_CHAT_OUTPUT_MAX_TOKENS, Integer.toString(outputMaxTokens), DESC_MAX_TOKENS, adminId);

        eventPublisher.publishEvent(new VirtualCrewChatConfigChangedEvent());
    }

    private void upsert(ConfigKey key, String value, String description, Long adminId) {
        Optional<SystemConfigData> existing = repository.findByConfigKey(key.value());
        if (existing.isPresent()) {
            SystemConfigData row = existing.get();
            row.updateValue(value, adminId);
            repository.save(row);
        } else {
            repository.save(SystemConfigData.create(key.value(), value, description, adminId));
        }
    }

    private boolean readBool(ConfigKey key, boolean fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) {
            return fallback;
        }
        String v = row.get().getConfigValue();
        if (v == null || v.isBlank()) {
            return fallback;
        }
        String trimmed = v.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private int readInt(ConfigKey key, int fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) {
            return fallback;
        }
        String v = row.get().getConfigValue();
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
