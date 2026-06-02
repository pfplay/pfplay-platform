package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 방별 최근 사람 메시지 롤링 버퍼(Redis capped list). 가상 DJ LLM 컨텍스트로 쓰인다.
 *
 * <p>{@code rightPush} 로 새 메시지를 꼬리에 붙이고 {@code trim} 으로 최근 {@link #MAX_SIZE} 개만
 * 남긴다. 방이 조용해지면 TTL({@link #TTL}) 로 자동 정리된다. 봇 발화는 호출자(트리거 게이트)가
 * loop guard 로 걸러 넣지 않는다 — 이 버퍼는 들어온 것을 그대로 보관할 뿐이다.
 */
@Service
@RequiredArgsConstructor
public class ChatContextBuffer {

    static final String KEY_PREFIX = "vdj:chat:ctx:";
    /** 버퍼 최대 보관 개수(LLM read 의 contextSize 와는 별개 — 그건 recent(n) 으로 따로 자른다). */
    static final int MAX_SIZE = 50;
    /** 방이 조용해지면 컨텍스트를 비우는 TTL. */
    static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;

    private static String keyOf(PartyroomId partyroomId) {
        return KEY_PREFIX + partyroomId.getId();
    }

    /**
     * 메시지 1건을 꼬리에 추가하고, 최근 {@link #MAX_SIZE} 개로 capping 한 뒤 TTL 을 갱신한다.
     */
    public void append(PartyroomId partyroomId, String content) {
        String key = keyOf(partyroomId);
        redisTemplate.opsForList().rightPush(key, content);
        redisTemplate.opsForList().trim(key, -MAX_SIZE, -1);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 최근 {@code n} 개 메시지를 오래된→최신 순으로 반환한다. 비어있거나 키가 없으면 빈 리스트.
     */
    public List<String> recent(PartyroomId partyroomId, int n) {
        List<Object> range = redisTemplate.opsForList().range(keyOf(partyroomId), -n, -1);
        if (range == null || range.isEmpty()) {
            return List.of();
        }
        return range.stream().map(String::valueOf).toList();
    }
}
