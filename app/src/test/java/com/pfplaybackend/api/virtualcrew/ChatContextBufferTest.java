package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.service.ChatContextBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatContextBuffer} 단위 테스트 — Redis capped list 동작을 mock 으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatContextBufferTest {

    private static final String KEY = "vcrew:chat:ctx:1";
    private static final int MAX_SIZE = 50;
    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    @Mock
    ListOperations<String, Object> listOps;

    ChatContextBuffer buffer;

    private final PartyroomId room = new PartyroomId(1L);

    @BeforeEach
    void setUp() {
        buffer = new ChatContextBuffer(redisTemplate);
    }

    @Test
    @DisplayName("append — 올바른 키로 rightPush + trim + expire 를 호출한다")
    void append_calls_rightPush_trim_expire() {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        buffer.append(room, "hello world");

        verify(listOps).rightPush(KEY, "hello world");
        verify(listOps).trim(KEY, -MAX_SIZE, -1);
        verify(redisTemplate).expire(KEY, TTL);
    }

    @Test
    @DisplayName("recent — range 결과(최근 n개)를 문자열 리스트로 반환한다")
    void recent_returns_range_result() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(eq(KEY), eq(-2L), eq(-1L)))
                .thenReturn(List.of("a", "b"));

        List<String> result = buffer.recent(room, 2);

        assertThat(result).containsExactly("a", "b");
    }

    @Test
    @DisplayName("recent — 빈 결과면 빈 리스트")
    void recent_empty_returns_empty_list() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(eq(KEY), eq(-5L), eq(-1L)))
                .thenReturn(List.of());

        assertThat(buffer.recent(room, 5)).isEmpty();
    }

    @Test
    @DisplayName("recent — null 결과(키 없음)면 빈 리스트")
    void recent_null_returns_empty_list() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(eq(KEY), eq(-5L), eq(-1L)))
                .thenReturn(null);

        assertThat(buffer.recent(room, 5)).isEmpty();
    }
}
