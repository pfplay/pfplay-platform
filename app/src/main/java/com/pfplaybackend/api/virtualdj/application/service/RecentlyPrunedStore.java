package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 최근 prune 된 linkId 를 봇별 Redis TTL set 으로 보관한다(진동 방지).
 *
 * <p>TTL({@link SelfUpdateConfig#prunedCooldownSeconds()}) 이 지나면 자동으로 set 이 삭제되어
 * 해당 곡이 다시 신규 후보로 고려될 수 있다.
 */
@Service
@RequiredArgsConstructor
public class RecentlyPrunedStore {

    public static final String KEY_PREFIX = "vdj:pruned:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SelfUpdateConfig config;

    private static String keyOf(UserId botUserId) {
        return KEY_PREFIX + botUserId.getUid();
    }

    /**
     * 주어진 linkId 들을 최근 prune 목록에 추가하고 TTL 을 갱신한다.
     * linkIds 가 비어있으면 아무 것도 하지 않는다.
     */
    public void markPruned(UserId botUserId, Collection<String> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) {
            return;
        }
        String key = keyOf(botUserId);
        redisTemplate.opsForSet().add(key, linkIds.toArray());
        redisTemplate.expire(key, Duration.ofSeconds(config.prunedCooldownSeconds()));
    }

    /**
     * 봇의 최근 prune set 을 반환한다.
     * 키가 없거나 만료됐으면 빈 set.
     */
    public Set<String> recentlyPruned(UserId botUserId) {
        Set<Object> members = redisTemplate.opsForSet().members(keyOf(botUserId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return members.stream()
                .map(String::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
