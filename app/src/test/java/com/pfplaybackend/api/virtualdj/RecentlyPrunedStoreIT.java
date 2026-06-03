package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualdj.application.service.RecentlyPrunedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecentlyPrunedStoreIT extends AbstractIntegrationTest {

    @Autowired private RecentlyPrunedStore store;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("markPruned 후 recentlyPruned 에서 추가한 linkId 조회 가능, 없는 것은 미포함")
    void markPruned_후_조회() {
        UserId bot = new UserId(7L);

        store.markPruned(bot, List.of("x", "y"));

        Set<String> pruned = store.recentlyPruned(bot);
        assertThat(pruned).contains("x", "y");
        assertThat(pruned).doesNotContain("z");
    }

    @Test
    @DisplayName("markPruned 후 TTL 이 양수로 설정된다")
    void markPruned_TTL_양수() {
        UserId bot = new UserId(8L);
        store.markPruned(bot, List.of("a"));

        String key = RecentlyPrunedStore.KEY_PREFIX + bot.getUid();
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("빈 linkIds 로 markPruned 하면 아무 것도 저장되지 않는다")
    void markPruned_빈_컬렉션_noop() {
        UserId bot = new UserId(9L);
        store.markPruned(bot, List.of());

        Set<String> pruned = store.recentlyPruned(bot);
        assertThat(pruned).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 봇의 set 은 독립적이다")
    void 다른봇_set_독립() {
        UserId botA = new UserId(10L);
        UserId botB = new UserId(11L);

        store.markPruned(botA, List.of("shared", "only-a"));
        store.markPruned(botB, List.of("shared", "only-b"));

        assertThat(store.recentlyPruned(botA)).contains("only-a").doesNotContain("only-b");
        assertThat(store.recentlyPruned(botB)).contains("only-b").doesNotContain("only-a");
    }
}
