# 가상 DJ P3-B 플레이리스트 반응 적응 자가갱신 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 봇 playlist 가 그 방의 청취자 반응(좋아요/싫어요/grab)에 적응해, 저반응 곡을 빼고 LLM 추천(실패 시 송팩 폴백)으로 채워 원자적 증분 교체한다.

**Architecture:** 기존 `VirtualDjReconcileScheduler`(60s) 에 자가갱신 패스를 얹는다. 룸당 값싼 COUNT 게이트(새 반응 < K → no-op, LLM 0)를 통과한 룸에서만 봇별 score 계산 → prune 후보(재생중/커서/최근prune 제외) → LLM 추천→Pytube 해소 → 부족분 송팩 폴백 → `added` 수만큼만 prune(크기 하한 보장) → watermark 전진. 모든 cross-BC 읽기는 query service/cross-BC query repo 경유(ArchUnit 준수). 외부 LLM 은 best-effort 빈 리스트 계약.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, MySQL(Flyway V29), Redis(최근prune set), Testcontainers(IT), ArchUnit, JUnit5/AssertJ/Mockito.

**Spec:** `docs/superpowers/specs/2026-06-03-virtual-dj-p3-playlist-self-update-design.md`

**빌드 prefix (모든 gradle 호출):** `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"`

---

## File Structure

**신규 파일:**
| 파일 | 책임 |
|---|---|
| `app/.../operations/domain/value/ConfigKey.java` (수정) | 자가갱신 튜닝 키 상수 추가 |
| `app/.../virtualdj/application/service/SelfUpdateConfig.java` | `vdj.playlist.self_update.*` read 래퍼(fail-closed). `VirtualDjChatConfig` 미러 |
| `app/.../virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java` (수정) | `lastSelfUpdateAt` 컬럼 + `markSelfUpdated` |
| `app/.../resources/db/migration/V29__add_self_update_watermark_and_tuning.sql` | 컬럼 추가 + 튜닝 키 시드 |
| `app/.../virtualdj/application/dto/LinkReactionScore.java` | linkId + 점수 구성요소 DTO |
| `app/.../virtualdj/adapter/out/persistence/ReactionScoreQueryRepository.java` | cross-BC 반응 COUNT + linkId별 score 집계(interface) |
| `app/.../virtualdj/adapter/out/persistence/impl/ReactionScoreQueryRepositoryImpl.java` | 위 구현(JPQL) |
| `app/.../virtualdj/application/service/ReactionScoreReader.java` | 위 repo 를 감싸는 thin reader |
| `app/.../virtualdj/application/port/SongRecommendationProvider.java` | 우승곡+컨셉 → 곡명 리스트(interface) |
| `app/.../virtualdj/application/service/LlmSongRecommendationProvider.java` | 프롬프트 조립+`LlmChatProvider`+파싱(best-effort 빈 리스트) |
| `app/.../virtualdj/application/service/SongPackReservoir.java` | 송팩의 "미시도 곡" 조회(폴백 소스) |
| `app/.../virtualdj/application/service/RecentlyPrunedStore.java` | per-bot prune linkId Redis set + TTL |
| `app/.../virtualdj/application/service/BotPlaylistEditor.java` | 원자적 swap 트랙 기계(add-to-head n / prune n, 연속 order 보정) |
| `app/.../virtualdj/application/service/PlaylistSelfUpdateService.java` | 사이클 오케스트레이션(조립자) |
| `app/.../virtualdj/application/service/VirtualDjReconcileScheduler.java` (수정) | enabled 게이트 + 룸별 위임 |

**테스트:** 각 단위는 `app/src/test/.../virtualdj/...` 대응 위치. IT 는 `app/src/test/.../virtualdj/*IT.java`(`AbstractIntegrationTest` 상속).

**ArchUnit 주의:** 신규 클래스명에 "Orchestrator" 를 넣지 말 것. `ReactionScoreQueryRepositoryImpl` 은 party 의 `*AggregatePort`/`*MessagePublisher` 를 직접 의존하지 말 것(JPQL 로 엔티티 직접 조회는 허용 — `ActiveDjSnapshotQueryRepositoryImpl` 동일 패턴).

---

## Chunk 1: 설정 + 마이그레이션 기반

### Task 1: ConfigKey 자가갱신 튜닝 키 추가

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java`
- Test: `app/src/test/java/com/pfplaybackend/api/operations/domain/value/ConfigKeyTest.java` (없으면 생성)

- [ ] **Step 1: 실패 테스트 작성** — 새 키 상수가 유효 패턴이고 기대 문자열인지 검증

```java
// ConfigKeyTest.java 에 추가 (없으면 클래스 생성: package com.pfplaybackend.api.operations.domain.value; import org.junit.jupiter.api.Test; import static org.assertj.core.api.Assertions.*;)
@Test
void selfUpdateTuningKeys_haveExpectedValues() {
    assertThat(ConfigKey.VDJ_SELF_UPDATE_COOLDOWN_SECONDS.value()).isEqualTo("vdj.playlist.self_update.cooldown_seconds");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_MIN_REACTIONS.value()).isEqualTo("vdj.playlist.self_update.min_reactions");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_TARGET_SIZE.value()).isEqualTo("vdj.playlist.self_update.target_size");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_REPLACE_PER_CYCLE.value()).isEqualTo("vdj.playlist.self_update.replace_per_cycle");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_RECOMMEND_COUNT.value()).isEqualTo("vdj.playlist.self_update.recommend_count");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_REACTION.value()).isEqualTo("vdj.playlist.self_update.weight.reaction");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_GRAB.value()).isEqualTo("vdj.playlist.self_update.weight.grab");
    assertThat(ConfigKey.VDJ_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS.value()).isEqualTo("vdj.playlist.self_update.pruned_cooldown_seconds");
}
```

> ⚠️ `ConfigKey` 패턴은 `^[a-z0-9_]+(\.[a-z0-9_]+)*$` (점 구분 lowercase). `weight.reaction` 처럼 점이 더 있어도 통과한다(검증됨). 키 길이 ≤ 64 — 가장 긴 `vdj.playlist.self_update.pruned_cooldown_seconds`(46자) OK.

- [ ] **Step 2: 실패 확인** — `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*ConfigKeyTest"` → 컴파일 실패(상수 없음)

- [ ] **Step 3: 상수 추가** — `ConfigKey.java` 의 `VDJ_PLAYLIST_SELF_UPDATE_ENABLED` 아래에

```java
    public static final ConfigKey VDJ_SELF_UPDATE_COOLDOWN_SECONDS = new ConfigKey("vdj.playlist.self_update.cooldown_seconds");
    public static final ConfigKey VDJ_SELF_UPDATE_MIN_REACTIONS = new ConfigKey("vdj.playlist.self_update.min_reactions");
    public static final ConfigKey VDJ_SELF_UPDATE_TARGET_SIZE = new ConfigKey("vdj.playlist.self_update.target_size");
    public static final ConfigKey VDJ_SELF_UPDATE_REPLACE_PER_CYCLE = new ConfigKey("vdj.playlist.self_update.replace_per_cycle");
    public static final ConfigKey VDJ_SELF_UPDATE_RECOMMEND_COUNT = new ConfigKey("vdj.playlist.self_update.recommend_count");
    public static final ConfigKey VDJ_SELF_UPDATE_WEIGHT_REACTION = new ConfigKey("vdj.playlist.self_update.weight.reaction");
    public static final ConfigKey VDJ_SELF_UPDATE_WEIGHT_GRAB = new ConfigKey("vdj.playlist.self_update.weight.grab");
    public static final ConfigKey VDJ_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS = new ConfigKey("vdj.playlist.self_update.pruned_cooldown_seconds");
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java app/src/test/java/com/pfplaybackend/api/operations/domain/value/ConfigKeyTest.java
git commit -m "feat(vdj): P3-B 자가갱신 튜닝 ConfigKey 상수 추가"
```

---

### Task 2: SelfUpdateConfig read 래퍼

`VirtualDjChatConfig`(`app/.../virtualdj/application/service/VirtualDjChatConfig.java`) 를 그대로 미러. `SystemConfigCache.readInt`(양수만, fail-open)·`readBoolean`(fail-closed) 위임. weight 는 정수 퍼밀(per-mille, ‰)로 둔다 — `readInt` 가 double 미지원이라 정수로 받고 1000 으로 나눠 쓴다.

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/virtualdj/application/service/SelfUpdateConfig.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/SelfUpdateConfigTest.java`

- [ ] **Step 1: 실패 테스트** (mock `SystemConfigCache`, `VirtualDjChatConfigTest` 미러)

```java
package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SelfUpdateConfigTest {

    private final SystemConfigCache cache = mock(SystemConfigCache.class);
    private final SelfUpdateConfig config = new SelfUpdateConfig(cache);

    @Test
    void isEnabled_defaultsFalse_failClosed() {
        when(cache.readBoolean(eq(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1)); // fail-open passthrough of fallback
        assertThat(config.isEnabled()).isFalse();
        verify(cache).readBoolean(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED, false);
    }

    @Test
    void tuningGetters_delegateWithDefaults() {
        when(cache.readInt(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        assertThat(config.cooldownSeconds()).isEqualTo(1800);
        assertThat(config.minReactions()).isEqualTo(5);
        assertThat(config.targetSize()).isEqualTo(20);
        assertThat(config.replacePerCycle()).isEqualTo(3);
        assertThat(config.recommendCount()).isEqualTo(6);
        assertThat(config.prunedCooldownSeconds()).isEqualTo(3600);
        // weights: per-mille → double
        assertThat(config.weightReaction()).isEqualTo(1.0);   // 1000‰
        assertThat(config.weightGrab()).isEqualTo(2.0);       // 2000‰
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:test --tests "*SelfUpdateConfigTest"` → 컴파일 실패

- [ ] **Step 3: 구현**

```java
package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가상 DJ P3-B 플레이리스트 자가갱신 런타임 설정 읽기 래퍼.
 *
 * <p>{@link SystemConfigCache} fail-open readInt(양수만)/readBoolean 에 위임. {@link #isEnabled()} 는
 * 전역 kill switch 이며 <b>기본 잠금(false)</b>(fail-closed) — 행 부재/오타/캐시 실패 시 자가갱신은 켜지지
 * 않는다(V28 시드 참조). weight 는 readDouble 부재로 정수 퍼밀(‰)로 저장하고 1000 으로 나눠 double 로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SelfUpdateConfig {

    static final boolean DEFAULT_ENABLED = false;          // fail-closed
    static final int DEFAULT_COOLDOWN_SECONDS = 1800;      // 30분
    static final int DEFAULT_MIN_REACTIONS = 5;            // K
    static final int DEFAULT_TARGET_SIZE = 20;             // T
    static final int DEFAULT_REPLACE_PER_CYCLE = 3;        // P
    static final int DEFAULT_RECOMMEND_COUNT = 6;          // N
    static final int DEFAULT_PRUNED_COOLDOWN_SECONDS = 3600;
    static final int DEFAULT_WEIGHT_REACTION_PERMILLE = 1000;   // 1.0
    static final int DEFAULT_WEIGHT_GRAB_PERMILLE = 2000;       // 2.0

    private final SystemConfigCache cache;

    public boolean isEnabled() {
        return cache.readBoolean(ConfigKey.VDJ_PLAYLIST_SELF_UPDATE_ENABLED, DEFAULT_ENABLED);
    }

    public int cooldownSeconds() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS);
    }

    public int minReactions() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_MIN_REACTIONS, DEFAULT_MIN_REACTIONS);
    }

    public int targetSize() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_TARGET_SIZE, DEFAULT_TARGET_SIZE);
    }

    public int replacePerCycle() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_REPLACE_PER_CYCLE, DEFAULT_REPLACE_PER_CYCLE);
    }

    public int recommendCount() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_RECOMMEND_COUNT, DEFAULT_RECOMMEND_COUNT);
    }

    public int prunedCooldownSeconds() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_PRUNED_COOLDOWN_SECONDS, DEFAULT_PRUNED_COOLDOWN_SECONDS);
    }

    public double weightReaction() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_REACTION, DEFAULT_WEIGHT_REACTION_PERMILLE) / 1000.0;
    }

    public double weightGrab() {
        return cache.readInt(ConfigKey.VDJ_SELF_UPDATE_WEIGHT_GRAB, DEFAULT_WEIGHT_GRAB_PERMILLE) / 1000.0;
    }
}
```

- [ ] **Step 4: 통과 확인** — PASS
- [ ] **Step 5: 커밋** — `feat(vdj): SelfUpdateConfig 자가갱신 런타임 설정 래퍼(fail-closed)`

---

### Task 3: V29 마이그레이션 + watermark 컬럼/도메인 메서드

**Files:**
- Modify: `app/.../virtualdj/domain/entity/data/PartyroomVirtualDjConfigData.java`
- Create: `app/src/main/resources/db/migration/V29__add_self_update_watermark_and_tuning.sql`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/domain/entity/data/PartyroomVirtualDjConfigDataTest.java` (없으면 생성)

- [ ] **Step 1: 실패 테스트** — `markSelfUpdated` 가 watermark 세팅

```java
package com.pfplaybackend.api.virtualdj.domain.entity.data;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class PartyroomVirtualDjConfigDataTest {
    @Test
    void markSelfUpdated_setsWatermark() {
        PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.create(1L);
        assertThat(cfg.getLastSelfUpdateAt()).isNull();
        LocalDateTime t = LocalDateTime.of(2026, 6, 3, 12, 0);
        cfg.markSelfUpdated(t);
        assertThat(cfg.getLastSelfUpdateAt()).isEqualTo(t);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:test --tests "*PartyroomVirtualDjConfigDataTest"` → 컴파일 실패

- [ ] **Step 3a: 엔티티에 필드/메서드 추가** — `songPackId` 필드 아래

```java
    @Comment("마지막 자가갱신 시각(P3-B watermark 겸 쿨다운 기준). null=미실행")
    @Column(name = "last_self_update_at")
    private java.time.LocalDateTime lastSelfUpdateAt;
```
도메인 메서드(클래스 하단 `turnOff()` 아래):
```java
    /** P3-B 자가갱신 사이클 완료 표시(watermark 전진). */
    public void markSelfUpdated(java.time.LocalDateTime now) {
        this.lastSelfUpdateAt = now;
    }
```
> `@Builder` 생성자는 그대로 둔다(자가갱신 필드는 빌더에 넣지 않음 — 항상 null 로 시작). `@Getter` 가 `getLastSelfUpdateAt()` 자동 생성.

- [ ] **Step 3b: V29 마이그레이션 작성**

```sql
-- 가상 DJ P3-B 플레이리스트 자가갱신: watermark 컬럼 + 튜닝 키 시드.
-- ⚠️ V28 은 전역 enabled 게이트(기본 false)만 시드했다. 본 마이그레이션은 사이클 동작에 필요한
--    watermark 컬럼과 튜닝 system_config 행을 추가한다. enabled 는 여전히 false(구현 후 명시 활성화).

ALTER TABLE partyroom_virtual_dj_config
    ADD COLUMN last_self_update_at DATETIME NULL COMMENT 'P3-B 자가갱신 watermark(쿨다운 기준)';

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.playlist.self_update.cooldown_seconds', '1800', 'P3-B 자가갱신 룸별 최소 간격(초)'),
    ('vdj.playlist.self_update.min_reactions', '5', 'P3-B 갱신 트리거 새 반응 임계 K(미만이면 LLM 미호출)'),
    ('vdj.playlist.self_update.target_size', '20', 'P3-B 봇 playlist 목표 크기 T'),
    ('vdj.playlist.self_update.replace_per_cycle', '3', 'P3-B 사이클당 최대 교체 수 P'),
    ('vdj.playlist.self_update.recommend_count', '6', 'P3-B LLM 곡명 추천 수 N'),
    ('vdj.playlist.self_update.weight.reaction', '1000', 'P3-B score 순반응 가중치(퍼밀 ‰, 1000=1.0)'),
    ('vdj.playlist.self_update.weight.grab', '2000', 'P3-B score grab 가중치(퍼밀 ‰)'),
    ('vdj.playlist.self_update.pruned_cooldown_seconds', '3600', 'P3-B prune 곡 재추가 차단 기간(초)');
```

> ⚠️ V28 이 최신 슬롯임을 `ls app/src/main/resources/db/migration/ | tail` 로 재확인 후 V29 사용. 슬롯 점프 금지([[feedback_flyway_slot_renumber]]).

- [ ] **Step 4: 통과 확인** — `./gradlew :app:test --tests "*PartyroomVirtualDjConfigDataTest"` PASS

- [ ] **Step 5: 커밋** — `feat(vdj): V29 자가갱신 watermark 컬럼 + 튜닝 키 시드 + markSelfUpdated`

---

## Chunk 2: 반응 score 읽기 (cross-BC)

### Task 4: LinkReactionScore DTO + ReactionScoreQueryRepository interface

**점수 산식(spec §5.1):** linkId 별
`score = w_react·(Σlike − Σdislike) + w_grab·Σgrab`
저장은 raw 집계만(Σlike, Σdislike, Σgrab). weight 적용은 `ReactionScoreReader` 가 한다(테스트 용이).
**완주율 항 없음** — `playback.end_time` 은 예정 종료 epoch-millis 라 완주 신호 부재(spec §3). grab 이 대체.

**Files:**
- Create: `app/.../virtualdj/application/dto/LinkReactionScore.java`
- Create: `app/.../virtualdj/adapter/out/persistence/ReactionScoreQueryRepository.java`

- [ ] **Step 1: DTO 작성** (테스트는 Task 5 의 Impl IT 에서)

```java
package com.pfplaybackend.api.virtualdj.application.dto;

/**
 * 봇 자기 plays 를 link_id 로 group 한 반응 집계 (P3-B score 입력).
 *
 * @param linkId        곡 링크 식별자
 * @param likeSum       Σ like_count
 * @param dislikeSum    Σ dislike_count
 * @param grabSum       Σ grab_count
 */
public record LinkReactionScore(String linkId, long likeSum, long dislikeSum, long grabSum) {}
```

- [ ] **Step 2: Repository interface 작성**

```java
package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.application.dto.LinkReactionScore;
import java.time.LocalDateTime;
import java.util.List;

/**
 * P3-B 자가갱신용 cross-BC 반응 읽기 — playback / playback_aggregation / playback_reaction_history 를
 * 가로질러 봇 자기 plays 의 반응을 집계한다. {@link com.pfplaybackend.api.virtualdj.adapter.out.persistence
 * .ActiveDjSnapshotQueryRepository} 와 동일한 virtualdj-adapter cross-BC 패턴.
 *
 * <p>ArchUnit: "Orchestrator" 미포함 클래스이며 party *AggregatePort/*MessagePublisher 를 의존하지 않으므로
 * (JPQL 엔티티 조회만) 가드 통과.
 */
public interface ReactionScoreQueryRepository {

    /**
     * watermark 이후 봇 plays 에 달린 반응 row 수(비용 게이트, INV-2).
     * @param botUserIds 룸의 봇 user_account id 목록(비면 0)
     * @param partyroomId 룸 id
     * @param since watermark(null 이면 전체 기간)
     */
    long countReactionsSince(List<Long> botUserIds, long partyroomId, LocalDateTime since);

    /**
     * 한 봇의 plays 를 link_id 로 group 한 반응 집계(score 입력).
     * @param botUserId 봇 user_account id
     * @param partyroomId 룸 id
     */
    List<LinkReactionScore> aggregateByLink(long botUserId, long partyroomId);
}
```

- [ ] **Step 3: 컴파일 확인** — `./gradlew :app:compileJava` PASS
- [ ] **Step 4: 커밋** — `feat(vdj): ReactionScoreQueryRepository 포트 + LinkReactionScore DTO`

---

### Task 5: ReactionScoreQueryRepositoryImpl + IT

엔티티(`PlaybackData`, `PlaybackAggregationData`, `PlaybackReactionHistoryData`)를 조회해 linkId별
Σlike/Σdislike/Σgrab 집계. **완주율 계산 없음**(end_time 미사용).

> **구현 노트(실 컬럼/패턴):** `playback(user_id, partyroom_id, link_id, created_at)`,
> `playback_aggregation(@EmbeddedId PlaybackId, like_count, dislike_count, grab_count)`,
> `playback_reaction_history(playback_id, user_id, created_at, ...)`. 기존 cross-BC 읽기
> `ActiveDjSnapshotQueryRepositoryImpl` 는 **QueryDSL(`JPAQueryFactory`+Q-class)** 을 쓴다 — 일관성을 위해
> 동일하게 QueryDSL 권장(JPQL 도 가능). ⚠️ `PlaybackAggregationData` PK 는 `@EmbeddedId PlaybackId` 이므로
> `findAllById(...)` 인자는 `List<PlaybackId>`(raw Long 아님). Impl 작성 전 세 엔티티 필드명/연관 확인.
> 연관이 없으면 두 단계 조회(plays → playbackIds → aggregation/history)로 한다.

**Files:**
- Create: `app/.../virtualdj/adapter/out/persistence/impl/ReactionScoreQueryRepositoryImpl.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/adapter/out/persistence/ReactionScoreQueryRepositoryIT.java`

- [ ] **Step 1: 실패 IT 작성** — 실 DB(Testcontainers)에 봇 1·룸 1 + playback 2건(linkA 2회, linkB 1회) + aggregation + reaction_history 시드 후 count/aggregate 검증. (`AbstractIntegrationTest` 상속, `@Transactional`. 시드는 각 BC repository 직접 save.)

```java
// 핵심 단언 (시드 후):
// linkA: 2 plays, like 3 / dislike 1 / grab 1
// linkB: 1 play,  like 0 / dislike 2 / grab 0
// reaction_history rows: 일부는 since 이전, 일부는 이후
long cnt = repo.countReactionsSince(List.of(botId), roomId, watermark);
assertThat(cnt).isEqualTo(/* watermark 이후 reaction_history row 수 */);

List<LinkReactionScore> scores = repo.aggregateByLink(botId, roomId);
assertThat(scores).extracting(LinkReactionScore::linkId).containsExactlyInAnyOrder("linkA", "linkB");
LinkReactionScore a = scores.stream().filter(s -> s.linkId().equals("linkA")).findFirst().orElseThrow();
assertThat(a.likeSum()).isEqualTo(3);
assertThat(a.dislikeSum()).isEqualTo(1);
assertThat(a.grabSum()).isEqualTo(1);
```
> 다른 봇(user_id)·다른 룸의 plays 를 1건씩 섞어 시드해 필터(user_id IN botIds AND partyroom_id) 정확성도 검증.

- [ ] **Step 2: 실패 확인** — `./gradlew :app:integrationTest --tests "*ReactionScoreQueryRepositoryIT"` (또는 프로젝트의 IT 태스크) → 컴파일/빈 부재 실패

- [ ] **Step 3: Impl 구현** — `@Repository`, `@PersistenceContext EntityManager em` 또는 Spring Data + `@Query`. 두 단계 조회 권장:
  1. `countReactionsSince`: `SELECT COUNT(rh) FROM PlaybackReactionHistoryData rh WHERE rh.playbackId IN (SELECT p.id FROM PlaybackData p WHERE p.userId IN :botIds AND p.partyroomId = :roomId) AND (:since IS NULL OR rh.createdAt > :since)` (필드명은 엔티티 확인 후 정정). `botUserIds` 비면 즉시 0 반환.
  2. `aggregateByLink`: 봇 plays(`PlaybackData` where userId+partyroomId) 조회 → playbackId→linkId 매핑 → `PlaybackAggregationData` 일괄 조회(`findAllById(List<PlaybackId>)`) → linkId 별 Σlike/Σdislike/Σgrab 으로 fold. **end_time/duration 사용 안 함.**

- [ ] **Step 4: 통과 확인** — IT PASS
- [ ] **Step 5: 커밋** — `feat(vdj): ReactionScoreQueryRepositoryImpl + IT(linkId별 순반응/grab 집계)`

---

### Task 6: ReactionScoreReader (thin wrapper)

`ActiveDjSnapshotService` 미러 — repo 를 감싸고 도메인 친화 메서드 제공. weight 적용한 최종 score 정렬은 여기서.

**Files:**
- Create: `app/.../virtualdj/application/service/ReactionScoreReader.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/ReactionScoreReaderTest.java`

- [ ] **Step 1: 실패 테스트** (mock repo + mock SelfUpdateConfig) — weighted score 계산·정렬 검증

```java
// 예: linkA(like3,dislike1,grab1), linkB(like0,dislike2,grab0)
// w_react=1.0, w_grab=2.0
// scoreA = 1.0*(3-1) + 2.0*1 = 2.0 + 2.0 = 4.0
// scoreB = 1.0*(0-2) + 2.0*0 = -2.0
// → 오름차순(저점 먼저): [linkB, linkA]
when(config.weightReaction()).thenReturn(1.0);
when(config.weightGrab()).thenReturn(2.0);
when(repo.aggregateByLink(7L, 99L)).thenReturn(List.of(
        new LinkReactionScore("linkA", 3, 1, 1),
        new LinkReactionScore("linkB", 0, 2, 0)));
List<ScoredLink> result = reader.scoredAscending(new UserId(7L), new PartyroomId(99L));
assertThat(result).extracting(ScoredLink::linkId).containsExactly("linkB", "linkA");
assertThat(result.get(0).score()).isCloseTo(-2.0, within(1e-9));
```

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `ScoredLink(String linkId, double score)` record(중첩 또는 dto 패키지). `countNewReactions(botIds, roomId, since)` 는 repo 위임. `scoredAscending(botUserId, roomId)` 는 `aggregateByLink` → weight 적용 → score 오름차순 정렬.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(vdj): ReactionScoreReader(weighted score 정렬 + 반응 카운트)`

---

## Chunk 3: 후보 출처 (LLM / 송팩 / 진동방지)

### Task 7: SongRecommendationProvider 포트 + LLM 구현

**Files:**
- Create: `app/.../virtualdj/application/port/SongRecommendationProvider.java`
- Create: `app/.../virtualdj/application/service/LlmSongRecommendationProvider.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/LlmSongRecommendationProviderTest.java`

- [ ] **Step 1: 포트 작성**

```java
package com.pfplaybackend.api.virtualdj.application.port;

import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader.RoomContext;
import java.util.List;

/**
 * 고반응 우승 곡 + 방 컨셉 → 추천 곡명(검색 쿼리) 리스트. best-effort: 실패 시 빈 리스트.
 */
public interface SongRecommendationProvider {
    /**
     * @param roomContext 방 제목/소개/현재곡(컨셉)
     * @param winnerTitles 고반응 우승 곡 제목들(LLM 의 취향 단서)
     * @param count 원하는 추천 수 N
     * @return 추천 곡명/쿼리 리스트(0~count). 실패 시 빈 리스트(예외 없음).
     */
    List<String> recommend(RoomContext roomContext, List<String> winnerTitles, int count);
}
```

- [ ] **Step 2: 실패 테스트** (mock `LlmChatProvider`)

```java
// 빈 응답 → 빈 리스트
when(llm.complete(anyString(), anyString(), anyInt())).thenReturn("");
assertThat(provider.recommend(ctx, List.of("Song A"), 6)).isEmpty();

// 줄단위 응답 파싱 (번호/불릿/공백/빈줄 제거, count 절단)
when(llm.complete(anyString(), anyString(), anyInt()))
    .thenReturn("1. Daft Punk - Around the World\n- Justice - Genesis\n\n  M83 - Midnight City  \n");
assertThat(provider.recommend(ctx, List.of("Song A"), 6))
    .containsExactly("Daft Punk - Around the World", "Justice - Genesis", "M83 - Midnight City");

// count 절단
when(llm.complete(anyString(), anyString(), anyInt())).thenReturn("a\nb\nc\nd");
assertThat(provider.recommend(ctx, List.of(), 2)).containsExactly("a", "b");
```

- [ ] **Step 3: 구현** — system 프롬프트(방 컨셉 추종 + "곡명만 한 줄에 하나, 설명 금지" 규칙) + user(우승곡 목록) 조립 → `llm.complete(system, user, maxTokens)` → 줄단위 split, 선행 `"\d+[.)] "`/`"- "`/`"* "` 제거, trim, blank 제거, distinct, `count` 절단. 빈 문자열/예외(방어적 try-catch) → `List.of()`.

- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(vdj): SongRecommendationProvider 포트 + LLM 구현(best-effort 파싱)`

---

### Task 8: SongPackReservoir (미시도 곡 폴백 소스)

**Files:**
- Create: `app/.../virtualdj/application/service/SongPackReservoir.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/SongPackReservoirIT.java` (실 DB — `VirtualSongPackTrackRepository` 시드)

- [ ] **Step 1: 실패 IT** — 송팩 5곡 시드, `exclude={linkId2, linkId4}`, timeLimit 으로 1곡 초과 → `untried(packId, exclude, timeLimitMinutes, limit=10)` 가 [linkId1, linkId3, linkId5] 중 timeLimit 통과분만, exclude 제외, 순서대로 반환 검증.

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `SongPackApplier` 의 읽기 로직 재사용: `songPackTrackRepository.findBySongPackIdOrderByOrderNumberAsc(packId)` → `PlaybackTimeLimit.ofMinutes` 필터 → `exclude` linkId 제외 → `limit` 절단. 반환 타입은 후속 단계가 `TrackData` 로 만들 수 있도록 `record ReservoirTrack(String name, String linkId, Duration duration, String thumbnailImage)` 리스트.
  > 가능하면 `SongPackApplier` 의 필터 루프를 이 클래스로 추출해 `applyToBot` 도 재사용(DRY). 단, `applyToBot` 동작 회귀 없도록 기존 IT 그대로 GREEN 확인.

- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(vdj): SongPackReservoir 미시도 곡 폴백 소스(SongPackApplier 필터 추출)`

---

### Task 9: RecentlyPrunedStore (Redis TTL set)

P3-A 의 Redis 사용(SETNX 게이트키) 패턴 참고. per-bot key `vdj:pruned:{botUserId}`, 멤버=linkId, TTL=prunedCooldownSeconds. (Redis set + 전체 키 TTL — 멤버별 만료 불가하므로 키 단위 TTL 갱신 방식.)

**Files:**
- Create: `app/.../virtualdj/application/service/RecentlyPrunedStore.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/RecentlyPrunedStoreIT.java` (실 Redis — IT 하니스가 Redis 제공하는지 확인; 없으면 `@DataRedisTest` 또는 embedded)

> **구현 노트:** 기존 코드의 Redis 추상화를 먼저 확인(`StringRedisTemplate` 직접 사용 vs 래퍼). P3-A 게이트키
> 구현 파일을 grep(`SETNX`/`RedisTemplate`/`opsForValue`)해 동일 빈/패턴 재사용. IT 가 어려우면 인터페이스로
> 추출하고 fake 구현으로 단위 테스트 + 실 구현은 e2e 게이트에서 검증.

- [ ] **Step 1: 실패 테스트** — `markPruned(botId, ["x","y"])` 후 `contains(botId,"x")` true, `contains(botId,"z")` false. TTL 설정 검증(키 expire > 0).
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `markPruned(UserId, Collection<String> linkIds)`: `opsForSet().add(key, linkIds...)` + `expire(key, prunedCooldownSeconds, SECONDS)`. `Set<String> recentlyPruned(UserId)`: `opsForSet().members(key)` (null→빈셋). `SelfUpdateConfig.prunedCooldownSeconds()` 주입.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(vdj): RecentlyPrunedStore(prune linkId Redis TTL set, 진동 방지)`

---

## Chunk 4: 사이클 오케스트레이션

### Task 10: BotPlaylistEditor (원자적 swap 트랙 기계)

봇 playlist 의 track 행을 원자적으로 교체. `add` n곡 add-to-head + `prune` n곡 삭제 + 연속 order 보정. INV-1: `n = min(add.size, prune.size)`.

**Files:**
- Create: `app/.../virtualdj/application/service/BotPlaylistEditor.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/BotPlaylistEditorIT.java` (실 DB)

- [ ] **Step 1: 실패 IT** — playlist 5곡(order 1..5), `prune=[order4곡,order5곡 의 trackId]`, `add=[newX,newY]`(ReservoirTrack/해소결과 공통 입력 record) → 호출 후:
  - 삭제: 옛 4·5번 곡 없음
  - 추가: newX,newY 가 head(order 1,2)
  - 총 곡수 = 5(불변, INV-1)
  - order_number 연속(1..5), 중복 없음
```java
int swapped = botPlaylistEditor.swap(playlistId, pruneTrackIds, addTracks); // addTracks: List<NewTrack(name,linkId,duration,thumb)>
assertThat(swapped).isEqualTo(2);                       // min(2 add, 2 prune)
List<TrackData> after = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId));
assertThat(after).hasSize(5);
assertThat(after).extracting(TrackData::getLinkId).contains("newX", "newY");
assertThat(after).extracting(TrackData::getOrderNumber).doesNotHaveDuplicates();
```
> `NewTrack` record(공용 입력)도 이 Task 에서 정의: `record NewTrack(String name, String linkId, Duration duration, String thumbnailImage)` — `app/.../virtualdj/application/dto/NewTrack.java`. add=[] 또는 prune=[] 케이스(n=0, 변경 없음)도 IT 에 추가.

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** (`@Transactional`, 시그니처 `int swap(Long playlistId, List<Long> pruneTrackIds, List<NewTrack> addTracks)`):
  1. `n = min(addTracks.size(), pruneTrackIds.size())`. add 상위 n / prune 저점 n 만 사용(호출자가 정렬해 전달; editor 는 받은 순서 신뢰). n==0 이면 변경 없이 0 반환.
  2. prune: `trackRepository.deleteAllById(pruneTrackIds.subList(0,n))`.
  3. 남은 트랙 order 재정규화: `findAllByPlaylistId(new PlaylistId(playlistId))` → orderNumber asc 정렬 → 1..m 재할당(`reorder`) → save. (shiftUpOrderByDelete 다회보다 단순·안전.)
  4. add-to-head: 신규 n곡을 order 1..n, 기존 m곡을 n+1..n+m 로. 즉 재정규화 단계에서 기존을 n+1 부터 시작하도록 한 번에 계산해 saveAll.
  5. `NewTrack` → `TrackData.builder().playlistId(new PlaylistId(playlistId)).name(t.name()).linkId(t.linkId()).duration(t.duration()).orderNumber(i+1).thumbnailImage(t.thumbnailImage()).build()`.
  6. `return n;`
  > duration 은 `Duration`(공용 값객체). 해소결과(SearchResultRawDto.running_time 문자열)·ReservoirTrack 모두 `Duration` 으로 정규화해 `NewTrack` 에 담아 전달(상위 Task 11 책임).

- [ ] **Step 4: 통과 확인** — IT PASS(특히 order 연속·size 불변)
- [ ] **Step 5: 커밋** — `feat(vdj): BotPlaylistEditor 원자적 swap(add-to-head + prune, 크기 불변)`

---

### Task 10B: PartyroomQueryService.getCurrentPlaybackLinkId (INV-3 시임)

INV-3(현재곡 prune 보호)는 현재곡 **linkId** 가 필요한데, 기존 `getCurrentPlaybackName` 은 이름(String)만
준다. party BC 안에 AggregatePort 를 가둔 채 linkId 를 노출하는 형제 메서드를 신설한다(가상 DJ 패키지의
ArchUnit AggregatePort 의존 금지 준수 — 평이한 String 만 반환).

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomQueryService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/party/application/service/PartyroomQueryServiceTest.java` (기존; getCurrentPlaybackName 테스트 미러)

- [ ] **Step 1: 실패 테스트** — 활성 재생 시 현재 playback 의 linkId 반환 / 비활성·currentPlaybackId=null 시 null. (기존 `getCurrentPlaybackName` 테스트의 mock 셋업 미러: `aggregatePort.findPlaybackState` + `playbackQueryService.getPlaybackById`.)

- [ ] **Step 2: 실패 확인** — `./gradlew :app:test --tests "*PartyroomQueryServiceTest"` → 컴파일 실패

- [ ] **Step 3: 구현** — `getCurrentPlaybackName`(`:151`) 바로 아래에 미러 메서드:
```java
    /**
     * 현재 재생 중인 곡의 linkId 를 반환한다. 재생 비활성/곡 없음이면 {@code null}.
     * P3-B 자가갱신의 현재곡 prune 보호(INV-3)용. AggregatePort 는 party BC 안에 가두고 String 만 노출.
     */
    @Transactional(readOnly = true)
    public String getCurrentPlaybackLinkId(PartyroomId partyroomId) {
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        if (!playbackState.isActivated() || playbackState.getCurrentPlaybackId() == null) {
            return null;
        }
        return playbackQueryService.getPlaybackById(playbackState.getCurrentPlaybackId()).getLinkId();
    }
```
> `PlaybackData.getLinkId()` 존재(검증됨). `@Getter` 라 별도 추가 불필요.

- [ ] **Step 4: 통과 확인** — PASS
- [ ] **Step 5: 커밋** — `feat(party): getCurrentPlaybackLinkId 신설(P3-B INV-3 현재곡 보호 시임)`

---

### Task 11: PlaylistSelfUpdateService (사이클 조립자)

게이트(cooldown + count) → 봇별 사이클(score → protect → LLM refill → Pytube 해소 → 송팩 폴백 → swap) → watermark.

**룸 단위 게이트 + 봇 단위 갱신:**
- 룸 config 의 `lastSelfUpdateAt` = watermark/쿨다운(룸당 1개).
- count 게이트 = 룸의 모든 봇 plays 합산(`ActiveDjSnapshotService` 로 봇 id 목록).
- 통과 시 각 봇마다 자기 plays score 로 사이클. 끝나면 watermark=now 1회.

**Files:**
- Create: `app/.../virtualdj/application/service/PlaylistSelfUpdateService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/PlaylistSelfUpdateServiceTest.java` (협력자 전부 mock)

- [ ] **Step 1: 실패 단위 테스트** (mock: SelfUpdateConfig, ActiveDjSnapshotService, ReactionScoreReader, SongRecommendationProvider, PytubeSearchService, SongPackReservoir, RecentlyPrunedStore, BotPlaylistEditor, configRepository, 현재곡 reader, RoomContextReader, VirtualUserPoolService). 케이스:
  1. **count < K → no-op**: `reader.countNewReactions(...)` = K-1 → `recommendationProvider`·`botPlaylistEditor` 호출 0, watermark 미전진(`configRepository.save` 0회 또는 markSelfUpdated 미호출). (INV-2)
  2. **cooldown 미경과 → no-op**: `lastSelfUpdateAt` = now-10s, cooldown=1800 → no-op.
  3. **count ≥ K & cooldown 경과 → 갱신**: score 저점 P곡 prune 후보(현재곡/커서/최근prune 제외) + LLM 추천 해소 → `botPlaylistEditor.swap` 호출, watermark 전진(`markSelfUpdated`).
  4. **LLM 빈손 → 송팩 폴백**: `recommend` = [] → `reservoir.untried(...)` 로 add 채워 `swap` 호출(added>0). (폴백 경로)
  5. **LLM·송팩 둘 다 빈손 → swap add=0**: editor 가 n=0 처리(prune 0, 변경 없음). watermark 는 **전진**(아래 결정 참조).
  6. **현재곡 prune 제외(INV-3)**: 현재곡 linkId 가 score 최저여도 prune 후보에서 빠짐.

  > 결정: 케이스 5 에서 watermark 는 **전진시킨다**(시도했고 비용 게이트 통과했으므로 쿨다운 적용해 재시도 폭주 방지). 테스트로 고정.

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `tryUpdateRoom(PartyroomId roomId)`. ⚠️ 실 시그니처 주의(리뷰 반영):
  - `configRepository.findByPartyroomId(roomId.getId())` → `Optional<PartyroomVirtualDjConfigData>` (PK=Long, **findById 아님**; `VirtualDjOrchestratorImpl.doReconcile` 동일).
  - 룸 playbackTimeLimit = `partyroomQueryService.getPartyroomById(roomId).getPlaybackTimeLimit().getMinutes()` (`VirtualDjOrchestratorImpl.addBots` 동일 경로).
  - 현재곡 linkId = `partyroomQueryService.getCurrentPlaybackLinkId(roomId)` (Task 10B 신설, null 가능).
```
cfgOpt = configRepository.findByPartyroomId(roomId.getId()); if empty or status != MANAGED → return
config = cfgOpt.get()
if (config.lastSelfUpdateAt != null && now - lastSelfUpdateAt < cooldown) return        // cooldown (null=첫 사이클 통과)
snapshot = activeDjSnapshotService.snapshot(roomId)
botIds = snapshot.botUserIdsByJoinedDesc(); if empty → return                            // List<UserId>
botIdLongs = botIds.map(UserId::getUid)
if (reader.countNewReactions(botIdLongs, roomId.getId(), config.lastSelfUpdateAt) < K) return   // INV-2 ⭐ LLM 전에
roomCtx = roomContextReader.read(roomId)
nowPlayingLinkId = partyroomQueryService.getCurrentPlaybackLinkId(roomId)                // null 가능
limitMin = partyroomQueryService.getPartyroomById(roomId).getPlaybackTimeLimit().getMinutes()
for botUserId in botIds:
    try { updateBot(botUserId, roomId, roomCtx, nowPlayingLinkId, limitMin, config.songPackId) }  // 봇 단위 예외 격리
    catch (Exception e) { log.warn(...) }
config.markSelfUpdated(now); configRepository.save(config)                               // watermark 1회
```
`updateBot(botUserId, roomId, roomCtx, nowPlayingLinkId, limitMin, songPackId)`:
```
scored = reader.scoredAscending(botUserId, roomId)                          // List<ScoredLink> 오름차순
playlistIdLong = virtualUserPoolService.playlistIdOf(botUserId)             // Long
tracks = trackRepository.findAllByPlaylistId(new PlaylistId(playlistIdLong))// ⚠️ PlaylistId 래핑
trackLinkIds = tracks.map(getLinkId)
protectedLinks = nonNull(nowPlayingLinkId) ∪ cursorAndNext(playlistIdLong, tracks)
                 ∪ recentlyPrunedStore.recentlyPruned(botUserId)
pruneCandidates = scored.filter(linkId ∈ trackLinkIds AND linkId ∉ protectedLinks)
                        .take(P) → 각 linkId 의 trackId 매핑(tracks 에서)
winnerTitles = scored(점수 내림차순) 상위에서 곡명(tracks 의 name) 추출
recommended = recommendationProvider.recommend(roomCtx, winnerTitles, N)    // best-effort, 빈 리스트 가능
resolved = []   // List<NewTrack>
for q in recommended:
    raw = pytube.searchByWord(q, 1).data() 의 첫 요소(없으면 skip)
    d = Duration.fromString(raw.running_time())
    if !PlaybackTimeLimit.ofMinutes(limitMin).exceedsDuration(d)
       AND raw.video_id ∉ (trackLinkIds ∪ protectedLinks ∪ resolved.linkIds):
        resolved += new NewTrack(raw.video_title, raw.video_id, d, raw.thumbnail_url)
if resolved.size < pruneCandidates.size:
    need = pruneCandidates.size − resolved.size
    fill = reservoir.untried(songPackId, exclude=trackLinkIds ∪ resolved.linkIds ∪ protectedLinks, limitMin, need)
    resolved += fill.map(→ NewTrack)
swappedN = botPlaylistEditor.swap(playlistIdLong, pruneCandidates.trackIds, resolved)   // editor 가 n=min 적용, 반환=실제 swap 수
prunedLinkIds = pruneCandidates 의 앞 swappedN 곡 linkId
recentlyPrunedStore.markPruned(botUserId, prunedLinkIds)
```
> `cursorAndNext(playlistIdLong, tracks)`: `PlaylistRepository.findById(playlistIdLong)` 로 `PlaylistData` 로드 →
> `getLastPlayedTrackId()`(커서, null 가능) 의 linkId + 그 트랙 order_number 다음 트랙의 linkId 반환(INV-3
> 임박 보호). 커서 null 이면 빈셋. (PlaylistRepository 는 playlist BC repo — 비-Orchestrator 클래스라 ArchUnit 허용.)
> `BotPlaylistEditor.swap(...)` 은 Task 10 에서 **실제 swap 한 곡 수(int)를 반환**하도록 시그니처 확정(markPruned 정확도).

- [ ] **Step 4: 통과 확인** — 6 케이스 PASS
- [ ] **Step 5: 커밋** — `feat(vdj): PlaylistSelfUpdateService 사이클 조립(게이트+score+refill+폴백+swap+watermark)`

---

### Task 12: VirtualDjReconcileScheduler 자가갱신 패스 + ArchUnit 확인

**Files:**
- Modify: `app/.../virtualdj/application/service/VirtualDjReconcileScheduler.java`
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/application/service/VirtualDjReconcileSchedulerTest.java` (없으면 생성; mock orchestrator/service/config/repo)

- [ ] **Step 1: 실패 테스트** — enabled=false 면 `playlistSelfUpdateService` 호출 0; enabled=true 면 MANAGED 룸마다 `tryUpdateRoom` 호출. 한 룸 예외가 다른 룸 호출 막지 않음(격리).

- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — 의존성에 `SelfUpdateConfig selfUpdateConfig`, `PlaylistSelfUpdateService playlistSelfUpdateService` 추가. `reconcileManagedRooms()` 의 기존 reconcile 루프는 유지하고, 그 뒤(또는 같은 루프 내 별도 try/catch)에:
```java
if (selfUpdateConfig.isEnabled()) {
    for (PartyroomVirtualDjConfigData cfg : managed) {
        try {
            playlistSelfUpdateService.tryUpdateRoom(new PartyroomId(cfg.getPartyroomId()));
        } catch (Exception e) {
            log.warn("[vdj-cron] self-update failed for partyroomId={} : {}", cfg.getPartyroomId(), e.getMessage());
        }
    }
}
```
> `managed` 리스트는 기존 reconcile 에서 이미 조회 → 재사용. enabled=false 면 루프 자체를 건너뛰어 비용 0(INV-4).

- [ ] **Step 4: 통과 확인** — scheduler 테스트 PASS
- [ ] **Step 5: ArchUnit 확인** — `./gradlew :app:test --tests "*VirtualDjArchitectureTest"` GREEN(신규 클래스가 "Orchestrator" 미포함, party AggregatePort/MessagePublisher 미의존). 위반 시 클래스명/의존 정정.
- [ ] **Step 6: 커밋** — `feat(vdj): reconcile cron 에 자가갱신 패스(enabled 게이트 + 룸 격리)`

---

## Chunk 5: 통합 검증 + e2e 게이트

### Task 13: PlaylistSelfUpdateIT (불변식 실 DB 검증)

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/virtualdj/PlaylistSelfUpdateIT.java` (`VirtualDjOrchestratorIT` 하니스 미러 — `AbstractIntegrationTest`, `@Transactional`, `UserProfileQueryPort` @MockBean, 아바타 시드)

봇 + MANAGED 룸 + 봇 playlist + 송팩을 실 DB 시드. `SongRecommendationProvider`(또는 LlmChatProvider) 와 `PytubeSearchService` 는 `@MockBean` 으로 격리(외부 호출 없이).

- [ ] **Step 1: IT 작성 — 4 시나리오**
  1. **빈 방 LLM 0(INV-2):** 반응 0 시드 → `tryUpdateRoom` → `verify(recommendationProvider, never()).recommend(...)`, playlist 불변, watermark 미전진.
  2. **반응 ≥ K → 1회 갱신:** reaction_history K건 + score 차등 시드, recommendation mock 이 해소가능 곡명 반환, pytube mock 이 곡 해소 → playlist 의 저점 곡이 신곡으로 교체, size 불변, watermark 전진. 쿨다운 내 재호출 → no-op.
  3. **LLM 실패 → 송팩 폴백, size 불변(INV-1):** recommendation mock = [] → 송팩 미시도 곡으로 채워짐, size == 직전 size.
  4. **현재곡 보호(INV-3):** 현재곡 linkId 가 최저 score 여도 갱신 후에도 playlist 에 잔존.

- [ ] **Step 2: 실패 확인** — `./gradlew :app:integrationTest --tests "*PlaylistSelfUpdateIT"`
- [ ] **Step 3: (필요 시 구현 보정)** — IT 가 드러낸 빈 부재/트랜잭션/매핑 버그 수정([[reference_ddl_auto_create_drop_hides_migration_drift]] 류 deploy-blocker 포착 목적).
- [ ] **Step 4: 통과 확인** — IT PASS
- [ ] **Step 5: 커밋** — `test(vdj): PlaylistSelfUpdateIT 불변식(INV-1/2/3) 실 DB 검증`

---

### Task 14: 전체 빌드 + 마이그레이션 validate 부팅 + 로컬 풀스택 e2e 게이트

**dev 머지 전 필수 게이트**([[feedback_local_e2e_before_dev_merge]], [[reference_local_docker_compose]]). 단위/통합 GREEN ≠ 배포 안전.

- [ ] **Step 1: 전체 테스트** — `JAVA_HOME=... ./gradlew :app:test` 전체 GREEN(P3-A 1218건 회귀 없음 + 신규).
- [ ] **Step 2: integrationTest 전체** — `./gradlew :app:integrationTest`(프로젝트 IT 태스크명 확인) GREEN.
- [ ] **Step 3: ArchUnit** — `*VirtualDjArchitectureTest` GREEN(이미 :app:test 포함이면 생략).
- [ ] **Step 4: bootJar + validate 부팅** — `./gradlew :app:bootJar` 후 로컬 docker-compose(`docker-compose.local.yml` + `.env.local`, `local` profile)로 **V29 포함 Flyway validate 부팅 성공** 확인. crash-loop/DDL drift 없음([[reference_ddl_auto_create_drop_hides_migration_drift]]). ⚠️ Dockerfile 이 호스트 `app/build/libs/*.jar` 복사 → bootJar 선행 필수.
- [ ] **Step 5: 라이브 e2e(수동 토글)** — `UPDATE system_config SET config_value='true' WHERE config_key='vdj.playlist.self_update.enabled'` 후, MANAGED 룸+봇+반응 시드 상태에서 cron 1~2 tick 관찰: 반응 K 미만 룸 = 로그상 LLM 미호출, 반응 충분 룸 = playlist 1회 교체. (Anthropic 키 미준비 시 recommendation 은 빈 응답→송팩 폴백 경로로 size 불변 관찰 — 계약은 단위로 검증됨.)
- [ ] **Step 6: 최종 커밋/정리** — 마이크로 커밋을 논리 단위로 squash([[feedback_commit_consolidation_before_push]]). dev 머지는 **사용자 게이트**(자동 금지). 한글 커밋([[feedback_korean_issue_commit_pr]]).

---

## 완료 기준 (Definition of Done)

- [ ] 6 핵심 결정(목적/score/후보/케이던스/범위/실패경로) 코드 반영
- [ ] INV-1~INV-6 각각 테스트로 강제(단위 또는 IT)
- [ ] `:app:test` + `:app:integrationTest` + ArchUnit GREEN
- [ ] V29 포함 로컬 풀스택 validate 부팅 성공
- [ ] enabled 기본 false 유지(fail-closed), 토글로만 활성
- [ ] dev 머지 = 사용자 게이트(자동 금지). prod 는 P3 전체+P1 묶음 일괄 승격.
