package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomSessionDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.realtime.port.SessionCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Chat-only session cache: {@code sessionId → (partyroom, user, crew)} written on
 * STOMP SUBSCRIBE and read by {@code PartyroomChatCommandService.sendMessage}.
 *
 * <p><b>Two parallel, deliberately distinct session-tracking mechanisms.</b>
 * Presence authority is the user-session <i>registry</i> driven by WS
 * CONNECT/DISCONNECT (#209 #31, {@code RedisUserSessionStoreAdapter}). This
 * session <i>cache</i> is a SEPARATE chat-only mechanism keyed on SUBSCRIBE
 * write / UNSUBSCRIBE delete / TTL backstop. Do NOT collapse them: routing
 * presence back through this subscribe-timed cache would reintroduce the #31 bug.
 */
@Service
public class RedisSessionCacheAdapter implements SessionCachePort {
    private static final Logger logger = LoggerFactory.getLogger(RedisSessionCacheAdapter.class);
    private final RedisTemplate<String, Object> redisTemplate;
    private final PartyroomQueryPort partyroomQueryPort;

    /**
     * Self-healing backstop TTL (seconds) applied on every {@link #saveSessionCache}.
     * The clean path still deletes on UNSUBSCRIBE; this only bounds abnormal-close
     * orphans (1006, no UNSUBSCRIBE — DisconnectionEventListener correctly does not
     * delete since Task 1.5). Default 24h — far longer than any realistic live
     * subscribed session, and a re-SUBSCRIBE rewrites/refreshes the row.
     */
    private final long ttlSeconds;

    public RedisSessionCacheAdapter(
            RedisTemplate<String, Object> redisTemplate,
            PartyroomQueryPort partyroomQueryPort,
            @Value("${presence.session-cache.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.partyroomQueryPort = partyroomQueryPort;
        this.ttlSeconds = ttlSeconds;
    }

    @Transactional
    public void saveSessionCache(String sessionId, String userId, String destination) {
        UserId userIdObj = UserId.fromString(userId);
        String[] parts = destination.split("/");
        if(parts[1].equals("sub")) {
            String topic = parts[2];
            String separator = parts[3];
            if (topic.equals("partyrooms")) {
                Optional<PartyroomSessionDto> partyroomSessionDto = createSessionData(sessionId, userIdObj);
                if (partyroomSessionDto.isEmpty()) {
                    logger.warn("No active partyroom found for userId={}, sessionId={} — skipping session cache", userId, sessionId);
                    return;
                }
                PartyroomSessionDto sessionData = partyroomSessionDto.get();
                // #30 단일룸 가드 (백엔드 방어): 구독한 룸 id가 사용자의 권위 활성 룸
                // (REST tryEnter로 입장한 룸, getActivePartyroomByUserId)과 다르면
                // cross-room 거부 — 서버 측 연관(세션 캐시)을 기록하지 않는다.
                // STOMP는 negative-ACK가 없으므로 reject = silent non-registration.
                long authoritativeRoomId = sessionData.partyroomId().getId();
                if (!String.valueOf(authoritativeRoomId).equals(separator)) {
                    logger.warn("[guard] cross-room SUBSCRIBE rejected — userId={}, subscribedRoom={}, authoritativeRoom={}",
                            userId, separator, authoritativeRoomId);
                    return;
                }
                // TTL 백스톱: clean path는 UNSUBSCRIBE에서 delete하지만, 비정상 종료
                // orphan(1006, UNSUBSCRIBE 없음)은 이 TTL로만 self-heal된다.
                redisTemplate.opsForValue().set(sessionId, sessionData, ttlSeconds, TimeUnit.SECONDS);
            }
        }
    }

    @Transactional
    public void deleteSessionCache(String sessionId){
        redisTemplate.delete(sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<Object> getSessionCache(String sessionId) {
        Object object =  redisTemplate.opsForValue().get(sessionId);
        if (object == null) {
            return Optional.empty();
        }
        return Optional.of(object);
    }

    private Optional<PartyroomSessionDto> createSessionData(String sessionId, UserId userId) {
        Optional<ActivePartyroomDto> optional = partyroomQueryPort.getActivePartyroomByUserId(userId);
        if (optional.isPresent()) {
            PartyroomId partyroomId = new PartyroomId(optional.get().id());
            long crewId = optional.get().crewId();
            return Optional.of(new PartyroomSessionDto(sessionId, userId, partyroomId, crewId));
        }
        return Optional.empty();
    }
}
