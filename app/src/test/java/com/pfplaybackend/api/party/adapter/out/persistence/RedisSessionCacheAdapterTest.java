package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisSessionCacheAdapterTest {

    private static final long TTL_SECONDS = 86400L;

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOperations;
    @Mock PartyroomQueryPort partyroomQueryPort;

    RedisSessionCacheAdapter redisSessionCacheAdapter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisSessionCacheAdapter = new RedisSessionCacheAdapter(redisTemplate, partyroomQueryPort, TTL_SECONDS);
    }

    @Test
    @DisplayName("saveSessionCache — 파티룸 구독 시 세션이 저장된다")
    void saveSessionCachePartyroomSubscriptionSavesSession() {
        // given
        String sessionId = "session-123";
        String userIdStr = "1";
        String destination = "/sub/partyrooms/1";
        UserId userId = UserId.fromString(userIdStr);
        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                1L, false, 10L, true, new PlaybackId(1L), new CrewId(5L)
        );
        when(partyroomQueryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));

        // when
        redisSessionCacheAdapter.saveSessionCache(sessionId, userIdStr, destination);

        // then
        verify(valueOperations).set(eq(sessionId), any(), eq(TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("saveSessionCache — 비정상 종료 orphan self-heal을 위한 TTL 백스톱이 적용된다")
    void saveSessionCacheAppliesTtlBackstop() {
        // given
        String sessionId = "session-123";
        String userIdStr = "1";
        String destination = "/sub/partyrooms/1";
        UserId userId = UserId.fromString(userIdStr);
        ActivePartyroomDto activeDto = new ActivePartyroomDto(
                1L, false, 10L, true, new PlaybackId(1L), new CrewId(5L)
        );
        when(partyroomQueryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.of(activeDto));

        // when
        redisSessionCacheAdapter.saveSessionCache(sessionId, userIdStr, destination);

        // then — clean path는 UNSUBSCRIBE에서 delete하지만, 비정상 종료(1006, UNSUBSCRIBE 없음)
        // orphan은 이 TTL로만 만료된다. set(...) 에 TTL 인자가 실제로 전달되는지 검증한다.
        verify(valueOperations).set(eq(sessionId), any(), eq(TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("saveSessionCache — 활성 파티룸이 없으면 세션 캐시를 저장하지 않는다")
    void saveSessionCacheNoActivePartyroomSkips() {
        // given
        String sessionId = "session-123";
        String userIdStr = "1";
        String destination = "/sub/partyrooms/1";
        UserId userId = UserId.fromString(userIdStr);
        when(partyroomQueryPort.getActivePartyroomByUserId(userId)).thenReturn(Optional.empty());

        // when
        redisSessionCacheAdapter.saveSessionCache(sessionId, userIdStr, destination);

        // then
        verify(valueOperations, never()).set(any(), any());
    }

    @Test
    @DisplayName("deleteSessionCache — 세션이 삭제된다")
    void deleteSessionCacheDeletesFromRedis() {
        // given
        String sessionId = "session-123";

        // when
        redisSessionCacheAdapter.deleteSessionCache(sessionId);

        // then
        verify(redisTemplate).delete(sessionId);
    }

    @Test
    @DisplayName("getSessionCache — 세션이 있으면 반환한다")
    void getSessionCacheExistsReturnsPresent() {
        // given
        String sessionId = "session-123";
        Object cached = new Object();
        when(valueOperations.get(sessionId)).thenReturn(cached);

        // when
        Optional<Object> result = redisSessionCacheAdapter.getSessionCache(sessionId);

        // then
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("getSessionCache — 세션이 없으면 empty를 반환한다")
    void getSessionCacheNotExistsReturnsEmpty() {
        // given
        String sessionId = "session-123";
        when(valueOperations.get(sessionId)).thenReturn(null);

        // when
        Optional<Object> result = redisSessionCacheAdapter.getSessionCache(sessionId);

        // then
        assertThat(result).isEmpty();
    }
}
