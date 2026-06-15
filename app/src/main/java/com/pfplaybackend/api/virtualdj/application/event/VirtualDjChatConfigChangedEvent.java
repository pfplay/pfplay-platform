package com.pfplaybackend.api.virtualdj.application.event;

/**
 * 가상 DJ 채팅/자가갱신 설정이 어드민에 의해 변경됨을 알리는 도메인 이벤트.
 *
 * <p>{@code VirtualDjChatConfigAdminService.update} 가 커밋 직전에 publish 하고,
 * {@code VirtualDjChatConfigCacheInvalidator} 가 AFTER_COMMIT 에서 받아
 * {@code SystemConfigCache.invalidate()} 로 30s TTL 스냅샷을 즉시 무효화한다.
 * 페이로드는 비어 있다 — 이벤트 발생 자체가 신호이고, 캐시는 다음 read 때 전체 재적재한다.
 */
public record VirtualDjChatConfigChangedEvent() {}
