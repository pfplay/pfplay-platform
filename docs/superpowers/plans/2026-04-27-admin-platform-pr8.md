# PR 8: V7 partyroom_admin_action + Admin Partyroom Management API Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V7 마이그레이션(`partyroom_admin_action`) + 7개 어드민 파티룸 관리 endpoint(B-1~B-6 + B-8) 구현. PR 7에서 만들어두고 호출자 0건이었던 도메인 메서드(`suspend`/`restore`/`terminate`)에 어드민 진입 경로 연결, `displayFlag setter` 신설. 모든 어드민 액션은 동기 listener로 atomic 감사 기록.

**Architecture:** Administration BC가 `PartyroomAggregatePort`로 Party 직접 호출 (use-case port 미도입 — PR 11에서 재검토). Audit는 `@EventListener`(synchronous, same TX)로 atomic 보장. Cross-BC list/detail은 Administration BC의 admin-read repository에서 QueryDSL JOIN, ArchUnit으로 단방향 cross-BC 가드. Bulk action은 per-item TX (`AdminPartyroomTransactionalUnit` 별 bean으로 Spring AOP self-invocation 회피).

**Tech Stack:** Java 21, Spring Boot 3.2 (Spring Security 6.2, Spring Data JPA 3.2, Hibernate 6.4), Flyway 9, QueryDSL 5, Jackson 2 (JSON converter), JUnit 5, Mockito, AssertJ, ArchUnit, Testcontainers (MySQL 8 + Redis).

**Spec source (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-27-admin-platform-pr8-design.md` — 14 결정사항, 7 risk
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 8
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.B (B-1~B-8), §7 listing UI
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.4 V7 DDL

**Branching:** Continue on `feature/admin-auth-iam-schema`. Spec commit: `94e35915`. PR 8 builds on top.

**Out of scope (defer)** — spec §2.2 참조:
- B-7 PENALIZE_CREW listener (PR 9, V8 punisher_type 컬럼과 묶음)
- `recentReports` 실데이터 (PR 13)
- `recentPenalties.punisherType` 필드 (PR 9에서 V8 컬럼 도입 후 채움)
- 일괄 액션의 RESTORE/SET_FEATURED/SET_NORMAL/UPDATE_META (B-8 MVP는 TERMINATE/SUSPEND/SET_HIDDEN만)
- Use-case port 패턴 (PR 11)

---

## Atomic commit groupings

Per-task commits are the default. The following groups MUST land as a single commit so the tree stays green:

| Group | Tasks | Reason |
|---|---|---|
| **G1: V7 + entity + enums + repository + converter** | Tasks 1 + 2 + 3 + 4 + 5 | 컬럼 ↔ entity ↔ enum ↔ JsonMetadata converter는 boot-or-die 의존. JsonMetadata 없으면 entity 컴파일 깨짐. 단일 commit 필수. **배포 순서 V7 SQL ↔ 새 jar 분리 불가.** |
| **G2: 신규 도메인 이벤트 5종 + Party 도메인 메서드** | Tasks 6 + 7 | 5 이벤트와 `setDisplayFlagFeatured/Hidden/Normal` publisher는 같이. 이벤트 페이로드 시그니처와 listener 처리 일관성. |

기타 task들은 task별 독립 commit (default).

Within each group:
- Per-task step lists remain a checklist.
- **Skip the `git commit` step at the end of each task in the group.**
- Single combined commit at the end of the group's last task with the message specified there.

---

## Hard precondition (verify BEFORE Task 1)

PR 8 builds on PR 7 + spec/plan commits.

- [ ] **Step 1: Confirm spec commit on HEAD ancestry**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform"
git log --oneline -3
```

Expected: HEAD includes `94e35915 docs(spec): PR 8 design ...` (and PR 7 commits below). Working tree clean.

- [ ] **Step 2: Working tree clean**

```bash
git status -s
```

Expected: empty.

- [ ] **Step 3: V7 slot open in `db/migration/`**

```bash
ls app/src/main/resources/db/migration/ | grep -E '^V[0-9]'
```

Expected: V1, V2, V3, V4, V5, V6, V9, V13 present. **V7 must NOT exist.**

- [ ] **Step 4: JDK 21 environment**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew --version
```

Expected: Gradle ~8.10, JVM 21.0.x.

- [ ] **Step 5: Baseline build + test pass**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```

Expected: BUILD SUCCESSFUL. **예상 5-15분 (cold)** — Testcontainers MySQL boot 포함, Docker daemon 가동 필요. PR 7 follow-up 후 회귀 0 보장 필요.

---

## Chunk 1: G1 — V7 마이그레이션 + AdminAction 엔티티 + JsonMetadata converter

**Goal of chunk:** V7 SQL + `PartyroomAdminActionData` entity + 2 enums (`PartyroomAdminActionType`, `AdminActionTargetType`) + `PartyroomAdminActionRepository` + `JsonMetadata` VO + JPA `@Convert` converter — 모든 게 boot-or-die 의존이라 단일 G1 commit.

**End state of chunk:** V7 마이그까지 적용된 DB에서 어플리케이션 부팅 성공, `PartyroomAdminActionData.of(...)` 팩토리 호출 + repository 저장이 unit/IT 테스트로 그린.

### Task 1: V7 Flyway 마이그레이션 SQL

**Files:**
- Create: `app/src/main/resources/db/migration/V7__create_partyroom_admin_action.sql`

- [ ] **Step 1: V7 SQL 작성**

```sql
-- =====================================================
-- V7: Administration context — AdminAction aggregate
-- Spec: docs/superpowers/specs/2026-04-27-admin-platform-pr8-design.md §3
-- Plan: docs/superpowers/plans/2026-04-27-admin-platform-pr8.md Task 1
--
-- 어드민의 시스템 액션 감사 로그. Append-only.
-- 교차 컨텍스트 참조(partyroom_id, target_id)는 FK 없이 값 저장.
-- =====================================================

CREATE TABLE partyroom_admin_action (
    action_id          BIGINT       NOT NULL AUTO_INCREMENT,
    administrator_id   BIGINT       NOT NULL,
    action_type        VARCHAR(32)  NOT NULL,
    target_type        VARCHAR(16)  NOT NULL,
    target_id          BIGINT       NOT NULL,
    partyroom_id       BIGINT       NULL,
    reason             TEXT         NULL,
    metadata           JSON         NULL,
    occurred_at        DATETIME     NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_paa_administrator
        FOREIGN KEY (administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_paa_partyroom_time (partyroom_id, occurred_at DESC),
    INDEX idx_paa_administrator_time (administrator_id, occurred_at DESC),
    INDEX idx_paa_target (target_type, target_id, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: SQL syntax sanity check**

```bash
grep -E 'CREATE TABLE|FOREIGN KEY|INDEX' app/src/main/resources/db/migration/V7__create_partyroom_admin_action.sql | wc -l
```

Expected: `5` (CREATE TABLE 1 + FOREIGN KEY 1 + INDEX 3).

⚠️ **Skip commit** — G1 묶음.

### Task 2: `PartyroomAdminActionType` enum

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/PartyroomAdminActionType.java`

- [ ] **Step 1: enum 작성** — PR 8 한정 7개 값

```java
package com.pfplaybackend.api.administration.domain.enums;

public enum PartyroomAdminActionType {
    SUSPEND_PARTYROOM,
    RESTORE_PARTYROOM,
    TERMINATE_PARTYROOM,
    SET_FEATURED,
    SET_HIDDEN,
    SET_NORMAL,
    UPDATE_PARTYROOM_META
    // 추가 enum 값 (PENALIZE_CREW, CHANGE_MEMBER_TIER, WITHDRAW_MEMBER)는 PR 9/12에서 추가
    // 컬럼은 VARCHAR(32)라 마이그레이션 불필요
}
```

⚠️ **Skip commit** — G1.

### Task 3: `AdminActionTargetType` enum

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminActionTargetType.java`

- [ ] **Step 1: enum 작성** — PR 8 한정 1개 값

```java
package com.pfplaybackend.api.administration.domain.enums;

public enum AdminActionTargetType {
    PARTYROOM
    // CREW, MEMBER는 PR 9/12에서 추가
}
```

⚠️ **Skip commit** — G1.

### Task 4: `JsonMetadata` VO + JPA Converter

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/value/JsonMetadata.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/value/JsonMetadataConverter.java`

신규 infrastructure 1점 — `Map<String, Object>` wrapper + Jackson 직렬화.

- [ ] **Step 1: `JsonMetadata` VO 작성**

```java
package com.pfplaybackend.api.administration.domain.value;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * partyroom_admin_action.metadata JSON 컬럼의 typed wrapper.
 *
 * 빈 map / null 모두 안전:
 *  - {@code JsonMetadata.empty()} → DB NULL
 *  - {@code JsonMetadata.of(map)} → JSON 직렬화 (빈 map은 "{}" 가 아닌 NULL로 저장 — converter가 처리)
 *
 * Immutable: 내부 map은 unmodifiable.
 */
public final class JsonMetadata {

    private static final JsonMetadata EMPTY = new JsonMetadata(Map.of());

    private final Map<String, Object> data;

    private JsonMetadata(Map<String, Object> data) {
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(data);
    }

    public static JsonMetadata empty() {
        return EMPTY;
    }

    public static JsonMetadata of(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return EMPTY;
        return new JsonMetadata(data);
    }

    public Map<String, Object> data() {
        return data;
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonMetadata that)) return false;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(data);
    }

    @Override
    public String toString() {
        return "JsonMetadata" + data;
    }
}
```

- [ ] **Step 2: `JsonMetadataConverter` 작성**

```java
package com.pfplaybackend.api.administration.domain.value;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * JsonMetadata ↔ JSON String JPA Converter.
 *
 * - empty/null → DB NULL
 * - 직렬화 실패 시 IllegalStateException (보존 필수 데이터라 swallow 금지)
 *
 * Spring 컨텍스트 외에서 ObjectMapper 주입 불가 — Jackson 기본 instance 사용.
 * partyroom_admin_action.metadata는 단순 key/value Map이라 LocalDateTime/PolymorphicType 등 특수 직렬화 불필요.
 */
@Converter(autoApply = false)
public class JsonMetadataConverter implements AttributeConverter<JsonMetadata, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonMetadata attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JsonMetadata", e);
        }
    }

    @Override
    public JsonMetadata convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return JsonMetadata.empty();
        try {
            return JsonMetadata.of(MAPPER.readValue(dbData, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JsonMetadata: " + dbData, e);
        }
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};
}
```

- [ ] **Step 3: 단위 테스트 작성**

`app/src/test/java/com/pfplaybackend/api/administration/domain/value/JsonMetadataConverterTest.java`:

```java
package com.pfplaybackend.api.administration.domain.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMetadataConverterTest {

    private final JsonMetadataConverter converter = new JsonMetadataConverter();

    @Test
    @DisplayName("convertToDatabaseColumn — empty/null은 DB NULL로 저장")
    void emptyToNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(JsonMetadata.empty())).isNull();
    }

    @Test
    @DisplayName("convertToDatabaseColumn — Map → JSON 문자열")
    void mapToJson() {
        JsonMetadata meta = JsonMetadata.of(Map.of("flag", "FEATURED", "old", "NORMAL"));
        String json = converter.convertToDatabaseColumn(meta);
        assertThat(json).contains("\"flag\":\"FEATURED\"").contains("\"old\":\"NORMAL\"");
    }

    @Test
    @DisplayName("convertToEntityAttribute — null/blank → empty")
    void nullToEmpty() {
        assertThat(converter.convertToEntityAttribute(null).isEmpty()).isTrue();
        assertThat(converter.convertToEntityAttribute("  ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("convertToEntityAttribute — JSON 문자열 → Map")
    void jsonToMap() {
        JsonMetadata meta = converter.convertToEntityAttribute("{\"flag\":\"FEATURED\",\"x\":42}");
        assertThat(meta.data()).containsEntry("flag", "FEATURED").containsEntry("x", 42);
    }

    @Test
    @DisplayName("round-trip — Map → JSON → Map 동일성")
    void roundTrip() {
        Map<String, Object> original = Map.of("a", "1", "b", 2);
        String json = converter.convertToDatabaseColumn(JsonMetadata.of(original));
        JsonMetadata back = converter.convertToEntityAttribute(json);
        assertThat(back.data()).isEqualTo(original);
    }
}
```

- [ ] **Step 4: 테스트 실행 — GREEN**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.domain.value.JsonMetadataConverterTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 5 tests pass.

⚠️ **Skip commit** — G1.

### Task 5: `PartyroomAdminActionData` 엔티티 + Repository + G1 commit

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomAdminActionData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomAdminActionRepository.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomAdminActionRepositoryIT.java`

#### 5.1 Entity

- [ ] **Step 1: Entity 작성**

```java
package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.administration.domain.value.JsonMetadataConverter;
import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;

import java.time.LocalDateTime;

/**
 * 어드민의 시스템 액션 감사 로그. Append-only — setter 없음.
 *
 * V7 마이그레이션으로 도입. spec §3 / §6.3 참조.
 */
@AggregateRoot
@Entity
@Table(name = "PARTYROOM_ADMIN_ACTION")
@Getter
@DynamicInsert
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyroomAdminActionData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id")
    private Long id;

    @Column(name = "administrator_id", nullable = false)
    private Long administratorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private PartyroomAdminActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private AdminActionTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Convert(converter = JsonMetadataConverter.class)
    @Column(name = "metadata", columnDefinition = "JSON")
    private JsonMetadata metadata;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PartyroomAdminActionData(Long administratorId, PartyroomAdminActionType actionType,
                                     AdminActionTargetType targetType, Long targetId, Long partyroomId,
                                     String reason, JsonMetadata metadata, LocalDateTime occurredAt) {
        this.administratorId = administratorId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.partyroomId = partyroomId;
        this.reason = reason;
        this.metadata = metadata;
        this.occurredAt = occurredAt;
    }

    /** 모든 필드 명시적 입력. 호출자(listener)는 이벤트 페이로드에서 값 추출 후 호출. */
    public static PartyroomAdminActionData of(Long administratorId,
                                              PartyroomAdminActionType actionType,
                                              AdminActionTargetType targetType,
                                              Long targetId,
                                              Long partyroomId,
                                              String reason,
                                              JsonMetadata metadata,
                                              LocalDateTime occurredAt) {
        return PartyroomAdminActionData.builder()
                .administratorId(administratorId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .partyroomId(partyroomId)
                .reason(reason)
                .metadata(metadata == null ? JsonMetadata.empty() : metadata)
                .occurredAt(occurredAt)
                .build();
    }
}
```

#### 5.2 Repository

- [ ] **Step 2: Repository 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyroomAdminActionRepository extends JpaRepository<PartyroomAdminActionData, Long> {

    /** B-2 detail의 recentAdminActions 용 — 시간 역순 LIMIT 10. */
    List<PartyroomAdminActionData> findTop10ByPartyroomIdOrderByOccurredAtDesc(Long partyroomId);
}
```

#### 5.3 Integration Test

- [ ] **Step 3: IT 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PartyroomAdminActionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAdminActionRepository repository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    /**
     * fk_paa_administrator는 administrator(administrator_id) 참조.
     * Test profile은 spring.flyway.enabled=false + ddl-auto=create-drop이라 V5 seed가 자동 안 돌아감.
     * PR 6 IT 패턴 답습 — UserAccount + Administrator를 명시적으로 seed.
     */
    private Long superAdminId;

    @BeforeEach
    void seedAdmin() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(900L), "audit-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(900L));
        this.superAdminId = superAdmin.getAdministratorId();
    }

    @Test
    @DisplayName("save → findById round-trip + metadata JSON 직렬화 확인")
    void roundTrip() {
        PartyroomAdminActionData saved = repository.save(PartyroomAdminActionData.of(
                superAdminId,
                PartyroomAdminActionType.SET_FEATURED,
                AdminActionTargetType.PARTYROOM,
                42L, 42L,
                null,
                JsonMetadata.of(Map.of("old_flag", "NORMAL", "new_flag", "FEATURED")),
                LocalDateTime.now()
        ));

        PartyroomAdminActionData reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getActionType()).isEqualTo(PartyroomAdminActionType.SET_FEATURED);
        assertThat(reloaded.getTargetType()).isEqualTo(AdminActionTargetType.PARTYROOM);
        assertThat(reloaded.getMetadata().data())
                .containsEntry("old_flag", "NORMAL")
                .containsEntry("new_flag", "FEATURED");
    }

    @Test
    @DisplayName("metadata empty → DB NULL")
    void emptyMetadata() {
        PartyroomAdminActionData saved = repository.save(PartyroomAdminActionData.of(
                superAdminId,
                PartyroomAdminActionType.RESTORE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                43L, 43L,
                null, null,
                LocalDateTime.now()
        ));

        PartyroomAdminActionData reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getMetadata().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("findTop10ByPartyroomIdOrderByOccurredAtDesc — 시간 역순 LIMIT 10")
    void findTop10() {
        long partyroomId = 99L;
        for (int i = 0; i < 12; i++) {
            repository.save(PartyroomAdminActionData.of(
                    superAdminId,
                    PartyroomAdminActionType.UPDATE_PARTYROOM_META,
                    AdminActionTargetType.PARTYROOM,
                    partyroomId, partyroomId,
                    "iter " + i, null,
                    LocalDateTime.now().minusMinutes(11 - i)   // 0번이 가장 오래된 것, 11번이 최신
            ));
        }

        List<PartyroomAdminActionData> top10 = repository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(top10).hasSize(10);
        // 최신부터 — reason "iter 11", "iter 10", ..., "iter 2"
        assertThat(top10.get(0).getReason()).isEqualTo("iter 11");
        assertThat(top10.get(9).getReason()).isEqualTo("iter 2");
    }
}
```

- [ ] **Step 4: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepositoryIT" 2>&1 | tail -30
```

Expected: 3 tests pass. `@BeforeEach`가 administrator row를 seed하므로 FK 위반 없음.

⚠️ 만약 `AdministratorData.createSuperAdmin(900L)`이 user_account FK 검사 등으로 실패하면, PR 6 `AdministratorRepositoryIntegrationTest.java`의 seeding 코드를 1:1로 답습 (해당 IT가 그린이면 본 IT도 그린).

#### 5.4 G1 commit

- [ ] **Step 5: 전체 회귀 테스트** (V7 마이그 후 회귀 0 보장)

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: G1 묶음 commit**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-platform"
git add \
  app/src/main/resources/db/migration/V7__create_partyroom_admin_action.sql \
  app/src/main/java/com/pfplaybackend/api/administration/domain/enums/PartyroomAdminActionType.java \
  app/src/main/java/com/pfplaybackend/api/administration/domain/enums/AdminActionTargetType.java \
  app/src/main/java/com/pfplaybackend/api/administration/domain/value/JsonMetadata.java \
  app/src/main/java/com/pfplaybackend/api/administration/domain/value/JsonMetadataConverter.java \
  app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/PartyroomAdminActionData.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomAdminActionRepository.java \
  app/src/test/java/com/pfplaybackend/api/administration/domain/value/JsonMetadataConverterTest.java \
  app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/PartyroomAdminActionRepositoryIT.java
git commit -m "$(cat <<'EOF'
feat(administration): V7 partyroom_admin_action — entity + enums + JsonMetadata converter (PR 8 G1)

- V7 migration: append-only audit table with FK to administrator,
  cross-BC ID (partyroom_id, target_id) as loose ref, JSON metadata.
  3 indexes for time-ordered queries by partyroom/administrator/target.
- PartyroomAdminActionType enum (7 PR 8 values; PENALIZE_CREW deferred
  to PR 9, MEMBER actions to PR 12; column is VARCHAR(32) — no
  migration needed for future additions).
- AdminActionTargetType enum (PR 8 uses PARTYROOM only).
- JsonMetadata value object — immutable Map<String, Object> wrapper.
  empty/null both safe; round-trips through JsonMetadataConverter.
- JsonMetadataConverter — JPA AttributeConverter, Jackson-based JSON
  serialization. autoApply=false (entity opts in via @Convert).
- PartyroomAdminActionData entity — append-only (no setters), of(...)
  factory with explicit fields, BaseEntity timestamps inherited.
- Repository: findTop10ByPartyroomIdOrderByOccurredAtDesc for B-2 detail.
- IT covers round-trip, empty metadata → DB NULL, top-10 ordering.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: HEAD + diff stat 검증**

```bash
git log --oneline -1
git diff --stat HEAD~1
```

Expected: 마지막 commit이 G1, diff --stat에 9 files changed (V7 sql + 2 enums + 2 JsonMetadata + entity + repository + 2 tests).

---

## Chunk 2: G2 — 신규 도메인 이벤트 5종 + Party 도메인 메서드 (setDisplayFlag*) + listener 확장

**Goal of chunk:** Party 도메인이 PR 8 endpoint들이 publish할 5개 이벤트를 노출하고, `PartyroomData`에 `setDisplayFlagFeatured/Hidden/Normal` 도메인 메서드 신설. 그 후 기존 `PartyroomCounterListener` + `DomainEventRedisRelay` 확장 — 신규 이벤트 처리. publisher와 도메인 메서드는 G2 단일 commit, listener 확장은 그 후 task별 commit.

**End state of chunk:** 5개 신규 이벤트 클래스 + 3개 setDisplayFlag* 메서드 + counter listener에 reset 핸들러 + redis relay에 3개 신규 fanout — 모든 게 컴파일 + 단위/IT 테스트 그린.

### Task 6: 신규 도메인 이벤트 5종 (G2)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomTerminatedEvent.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomSuspendedEvent.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomRestoredEvent.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomMetaUpdatedEvent.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomDisplayFlagChangedEvent.java`

기존 `PartyroomClosedEvent` 패턴 답습 (`extends DomainEvent`, `@Getter`, getAggregateId).

#### 6.1 `PartyroomTerminatedEvent`

- [ ] **Step 1:**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

/**
 * 어드민 경로의 파티룸 종료 이벤트. host 자발 종료(PartyroomClosedEvent)와 별개.
 *
 * - PartyroomAdminActionListener가 listen → admin_action TERMINATE_PARTYROOM 기록
 * - PartyroomCounterListener가 listen → crew_count = 0 reset
 * - DomainEventRedisRelay가 listen → ROOM_TERMINATED topic publish
 *
 * occurredAt은 DomainEvent 기반 클래스가 LocalDateTime.now()로 자동 설정.
 */
@Getter
public class PartyroomTerminatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final String reason;

    public PartyroomTerminatedEvent(PartyroomId partyroomId, Long administratorId, String reason) {
        super();   // DomainEvent — occurredAt = LocalDateTime.now()
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

#### 6.2 `PartyroomSuspendedEvent`

- [ ] **Step 2:**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomSuspendedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final String reason;

    public PartyroomSuspendedEvent(PartyroomId partyroomId, Long administratorId, String reason) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.reason = reason;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

#### 6.3 `PartyroomRestoredEvent`

- [ ] **Step 3:**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomRestoredEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;

    public PartyroomRestoredEvent(PartyroomId partyroomId, Long administratorId) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

#### 6.4 `PartyroomMetaUpdatedEvent`

- [ ] **Step 4:**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

import java.util.Map;

/**
 * Admin이 partyroom 메타 정보(title/introduction/playbackTimeLimit)를 변경한 이벤트.
 *
 * diff는 변경된 필드만 포함. wire format은 spec §4.5/§6.1과 일치:
 *   {"title": {"old": "A", "new": "B"}, "introduction": {"old": "...", "new": "..."}}
 *
 * inner Map의 key는 literal "old" / "new" 문자열. (Java 키워드 충돌 회피 — record 사용 불가, Map<String, Object> 사용.)
 * publisher가 호출 전에 직접 Map 구성:
 *   Map.of("old", oldValue, "new", newValue)
 */
@Getter
public class PartyroomMetaUpdatedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final Map<String, Map<String, Object>> diff;

    public PartyroomMetaUpdatedEvent(PartyroomId partyroomId, Long administratorId,
                                     Map<String, Map<String, Object>> diff) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.diff = diff;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

#### 6.5 `PartyroomDisplayFlagChangedEvent`

- [ ] **Step 5:**

```java
package com.pfplaybackend.api.party.domain.event;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.Getter;

@Getter
public class PartyroomDisplayFlagChangedEvent extends DomainEvent {
    private final PartyroomId partyroomId;
    private final Long administratorId;
    private final DisplayFlag oldFlag;
    private final DisplayFlag newFlag;

    public PartyroomDisplayFlagChangedEvent(PartyroomId partyroomId, Long administratorId,
                                            DisplayFlag oldFlag, DisplayFlag newFlag) {
        super();
        this.partyroomId = partyroomId;
        this.administratorId = administratorId;
        this.oldFlag = oldFlag;
        this.newFlag = newFlag;
    }

    @Override
    public String getAggregateId() {
        return String.valueOf(partyroomId.getId());
    }
}
```

⚠️ **Skip commit** — G2 묶음.

### Task 7: `PartyroomData` setDisplayFlagFeatured/Hidden/Normal + G2 commit

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomDataTest.java` — `setDisplayFlag*` 테스트 nested class 추가

#### 7.1 단위 테스트 먼저 (TDD red)

- [ ] **Step 1: PartyroomDataTest에 nested 테스트 추가**

기존 파일 끝에 추가:

```java
@Nested
@DisplayName("setDisplayFlagFeatured/Hidden/Normal()")
class SetDisplayFlag {
    @Test @DisplayName("ACTIVE 룸 — FEATURED 설정")
    void featured_active() {
        PartyroomData p = newPartyroom();
        p.setDisplayFlagFeatured();
        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);
    }

    @Test @DisplayName("SUSPENDED 룸 — FEATURED 설정 가능 (운영 정책)")
    void featured_suspended() {
        PartyroomData p = newPartyroom();
        p.suspend();
        p.setDisplayFlagFeatured();
        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);
    }

    @Test @DisplayName("TERMINATED 룸 — ConflictException")
    void featured_terminated() {
        PartyroomData p = newPartyroom();
        p.terminate();
        assertThatThrownBy(p::setDisplayFlagFeatured).isInstanceOf(ConflictException.class);
    }

    @Test @DisplayName("HIDDEN 설정")
    void hidden() {
        PartyroomData p = newPartyroom();
        p.setDisplayFlagHidden();
        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.HIDDEN);
    }

    @Test @DisplayName("NORMAL 설정")
    void normal() {
        PartyroomData p = newPartyroom();
        p.setDisplayFlagFeatured();
        p.setDisplayFlagNormal();
        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
    }

    @Test @DisplayName("이미 같은 flag — 변경 없이 통과 (idempotent)")
    void idempotent() {
        PartyroomData p = newPartyroom();
        // default NORMAL → setNormal again
        assertThatNoException().isThrownBy(p::setDisplayFlagNormal);
        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
    }
}
```

`@Nested`/`assertThatNoException`은 이미 import됨 (PR 7).

- [ ] **Step 2: 테스트 실행 — RED**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PartyroomDataTest" 2>&1 | tail -20
```

Expected: `compileTestJava` 또는 신규 `SetDisplayFlag` 테스트 메서드 호출 실패 — `setDisplayFlag*` 메서드 미존재.

#### 7.2 `PartyroomData`에 메서드 추가

- [ ] **Step 3:** 기존 `// ── State Transitions ──` 섹션 뒤에 신규 섹션 추가:

```java
    // ── Display Flag (admin-only via Administration BC; ArchUnit 가드는 별 task) ──

    /**
     * displayFlag 변경. TERMINATED 룸은 거부 (ILLEGAL_STATE_TRANSITION).
     * SUSPENDED 룸은 허용 — admin이 정지 중에도 분류 라벨 변경 가능.
     * 같은 값 재설정은 idempotent (no exception, no event recommended at caller).
     */
    public void setDisplayFlagFeatured() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.FEATURED;
    }

    public void setDisplayFlagHidden() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.HIDDEN;
    }

    public void setDisplayFlagNormal() {
        guardDisplayFlagChangeable();
        this.displayFlag = DisplayFlag.NORMAL;
    }

    private void guardDisplayFlagChangeable() {
        if (this.status == PartyroomStatus.TERMINATED) {
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
    }
```

3 메서드 분리 (단일 `setDisplayFlag(DisplayFlag flag)` 대신) — audit listener의 action_type 분기를 publisher 호출 시점에서 명료화. spec §4.6 결정.

- [ ] **Step 4: 테스트 실행 — GREEN**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*PartyroomDataTest" 2>&1 | tail -20
```

Expected: 모든 테스트 (기존 + 신규 6개) GREEN.

#### 7.3 G2 묶음 commit

- [ ] **Step 5: 컴파일 확인 — 신규 5 이벤트 + 메서드 모두 컴파일**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: G2 commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomTerminatedEvent.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomSuspendedEvent.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomRestoredEvent.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomMetaUpdatedEvent.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/event/PartyroomDisplayFlagChangedEvent.java \
  app/src/main/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomData.java \
  app/src/test/java/com/pfplaybackend/api/party/domain/entity/data/PartyroomDataTest.java
git commit -m "$(cat <<'EOF'
feat(party): admin-context domain events + setDisplayFlag* methods (PR 8 G2)

Five new domain events with administrator/reason payloads — all extend
DomainEvent, all expose getAggregateId() = partyroomId:

- PartyroomTerminatedEvent (admin terminate; distinct from existing
  PartyroomClosedEvent for host-self-close — both fan out to Redis on
  separate topics, both reset crew_count via PartyroomCounterListener)
- PartyroomSuspendedEvent (reason payload)
- PartyroomRestoredEvent (no reason — restore is a release, not an action
  needing justification)
- PartyroomMetaUpdatedEvent (diff: Map<String, OldNewPair> for audit
  metadata — only changed fields captured by service before mutation)
- PartyroomDisplayFlagChangedEvent (oldFlag, newFlag for audit)

PartyroomData domain methods:
- setDisplayFlagFeatured / Hidden / Normal — three explicit methods
  (vs single setDisplayFlag(DisplayFlag) param) so the audit listener's
  action_type (SET_FEATURED/SET_HIDDEN/SET_NORMAL) is determined at
  publisher call site, not by listener inspecting payload.
- guardDisplayFlagChangeable() — TERMINATED rejected with
  ILLEGAL_STATE_TRANSITION; SUSPENDED allowed (admin can reclassify
  paused rooms).

Unit tests cover full matrix: ACTIVE/SUSPENDED/TERMINATED × 3 setters
+ idempotent same-flag-reset.

Listener wiring (PartyroomCounterListener / DomainEventRedisRelay
extensions, AdminPartyroomCommandService publish points) lands in
subsequent commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: HEAD + diff 검증**

```bash
git log --oneline -1
git diff --stat HEAD~1
```

Expected: 7 files changed (5 events + PartyroomData + test).

### Task 8: `PartyroomCounterListener` 확장 — `on(PartyroomTerminatedEvent)` + `on(PartyroomClosedEvent)`

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListener.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepository.java` — `resetCrewCount` 추가
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListenerIT.java` — 2 신규 테스트
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryAtomicUpdateIT.java` — `resetCrewCount` IT

#### 8.1 `PartyroomRepository.resetCrewCount` 추가

- [ ] **Step 1: 메서드 추가**

기존 atomic UPDATE 메서드들(incrementCrewCount/decrementCrewCount/touchLastActivity) 뒤에 추가:

```java
    /**
     * crew_count 절대값 0으로 reset. terminate / 자발 close 후 호출.
     * incrementCrewCount/decrementCrewCount의 incremental 모델과 다른 absolute reset.
     *
     * status 가드 없음 — TERMINATED 룸의 reset이 본 use case.
     * 이미 0인 row를 reset해도 멱등 (UPDATE 자체는 실행, 0 row affected 가능).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PartyroomData p SET p.crewCount = 0 WHERE p.id = :id")
    int resetCrewCount(@Param("id") Long id);
```

#### 8.2 `PartyroomCounterListener` 확장

- [ ] **Step 2: 2 신규 listener 메서드 추가**

기존 listener 메서드들 뒤에 추가:

```java
    /**
     * 어드민 경로 종료 — admin이 PartyroomTerminatedEvent publish.
     * crew_count = 0으로 reset (bulk crew deactivate는 publisher service에서 별도 처리).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PartyroomTerminatedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        int affected = partyroomRepository.resetCrewCount(partyroomId);
        log.info("[PartyroomCounterListener] crew_count reset for terminated partyroomId={}, affected={}",
                 partyroomId, affected);
    }

    /**
     * Host 자발 종료 — PartyroomClosedEvent (기존, PR 7부터 존재).
     * 동일하게 reset — counter 일관성 보장 (Risk #7 결정).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PartyroomClosedEvent event) {
        Long partyroomId = event.getPartyroomId().getId();
        int affected = partyroomRepository.resetCrewCount(partyroomId);
        log.info("[PartyroomCounterListener] crew_count reset for closed partyroomId={}, affected={}",
                 partyroomId, affected);
    }
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
```

#### 8.3 `resetCrewCount` IT 추가

- [ ] **Step 3:** 기존 `PartyroomRepositoryAtomicUpdateIT`에 추가:

```java
    @Test
    @DisplayName("resetCrewCount — 임의 값 → 0")
    void reset_normal() {
        PartyroomData p = createAndSaveActive(1010L);
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());

        int affected = partyroomRepository.resetCrewCount(p.getId());

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("resetCrewCount — TERMINATED 룸도 reset 가능 (status 가드 없음)")
    void reset_terminated() {
        PartyroomData p = createAndSaveActive(1011L);
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());
        p.terminate();
        partyroomRepository.saveAndFlush(p);

        int affected = partyroomRepository.resetCrewCount(p.getId());

        assertThat(affected).isEqualTo(1);
        assertThat(partyroomRepository.findById(p.getId()).orElseThrow().getCrewCount()).isZero();
    }
```

#### 8.4 Listener IT 확장

- [ ] **Step 4: PartyroomCounterListenerIT에 신규 테스트 추가**

```java
    @Test
    @DisplayName("PartyroomTerminatedEvent → crew_count = 0 reset")
    void terminated_resets_count() {
        long roomId = createActiveRoom(3010L, "term-event");
        // 사전에 +5
        for (int i = 0; i < 5; i++) {
            transactionTemplate.executeWithoutResult(status ->
                    eventPublisher.publishEvent(new CrewAccessedEvent(
                            new PartyroomId(roomId), new CrewId(8000L + (long) i),
                            new UserId(8000L + (long) i), AccessType.ENTER))
            );
        }

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomTerminatedEvent(
                        new PartyroomId(roomId), 999L, "test reason"))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("PartyroomClosedEvent → crew_count = 0 reset (host 자발 종료 일관성)")
    void closed_resets_count() {
        long roomId = createActiveRoom(3011L, "closed-event");
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId), new CrewId(9000L), new UserId(9000L), AccessType.ENTER))
        );

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomClosedEvent(
                        new PartyroomId(roomId), new UserId(3011L), "closed-event"))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }
```

import 추가:
```java
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
```

- [ ] **Step 5: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*PartyroomRepositoryAtomicUpdateIT" --tests "*PartyroomCounterListenerIT" 2>&1 | tail -30
```

Expected: 모든 IT pass (기존 + 신규 4개).

- [ ] **Step 6: 전체 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListener.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepository.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/in/listener/PartyroomCounterListenerIT.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/PartyroomRepositoryAtomicUpdateIT.java
git commit -m "$(cat <<'EOF'
feat(party): PartyroomCounterListener resets crew_count on terminate/close (PR 8)

PartyroomRepository.resetCrewCount(Long): atomic absolute reset of
crew_count to 0. Distinct from increment/decrement (incremental model);
no status guard (TERMINATED rooms are the primary use case).

PartyroomCounterListener handles two new event types:
- PartyroomTerminatedEvent (admin path) → resetCrewCount(0)
- PartyroomClosedEvent (host-self path, pre-existing) → resetCrewCount(0)

Both run AFTER_COMMIT in REQUIRES_NEW (consistent with PR 7 listener
phase semantics: side-effects on commit, not atomic-with-publisher).
Risk #7 in spec §13 — counter consistency requires both events to reset.

ITs cover:
- resetCrewCount on normal + TERMINATED rooms
- Listener end-to-end: 5x ENTER then PartyroomTerminatedEvent → count == 0
- Listener end-to-end: 1x ENTER then PartyroomClosedEvent → count == 0

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 9: `MessageTopic` 확장 + 3 신규 Message DTO + `DomainEventRedisRelay` 확장

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/domain/enums/MessageTopic.java` — 3 신규 enum
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomTerminatedMessage.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomSuspendedMessage.java`
- Create: `app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomRestoredMessage.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/event/DomainEventRedisRelay.java` — 3 신규 listener

#### 9.1 `MessageTopic` 확장

- [ ] **Step 1: 3 신규 enum 추가**

기존 enum 값 뒤(PARTYROOM_CLOSED 이후) 추가:

```java
    PARTYROOM_CLOSED,
    ROOM_TERMINATED,    // PR 8 — admin terminate
    ROOM_SUSPENDED,     // PR 8 — admin suspend
    ROOM_RESTORED;      // PR 8 — admin restore
```

(세미콜론 위치 조정.)

#### 9.2 3 Message DTO 작성 (record, 기존 PartyroomDeactivationMessage 패턴)

- [ ] **Step 2: `RoomTerminatedMessage`**

```java
package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;

public record RoomTerminatedMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        Long administratorId,
        String reason
) implements Serializable, GroupBroadcastMessage {}
```

- [ ] **Step 3: `RoomSuspendedMessage`** — 동일 패턴

```java
package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;

public record RoomSuspendedMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        Long administratorId,
        String reason
) implements Serializable, GroupBroadcastMessage {}
```

- [ ] **Step 4: `RoomRestoredMessage`**

```java
package com.pfplaybackend.api.party.adapter.in.listener.message;

import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.io.Serializable;

public record RoomRestoredMessage(
        PartyroomId partyroomId,
        MessageTopic eventType,
        String id,
        long timestamp,
        Long administratorId
) implements Serializable, GroupBroadcastMessage {}
```

#### 9.3 `DomainEventRedisRelay` 확장

- [ ] **Step 5: 3 신규 listener 메서드 추가**

기존 listener 메서드들 뒤에 추가:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomTerminatedEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_TERMINATED.topic(),
                new RoomTerminatedMessage(event.getPartyroomId(), MessageTopic.ROOM_TERMINATED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId(), event.getReason()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomSuspendedEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_SUSPENDED.topic(),
                new RoomSuspendedMessage(event.getPartyroomId(), MessageTopic.ROOM_SUSPENDED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId(), event.getReason()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PartyroomRestoredEvent event) {
        messagePublisher.publish(MessageTopic.ROOM_RESTORED.topic(),
                new RoomRestoredMessage(event.getPartyroomId(), MessageTopic.ROOM_RESTORED,
                        UUID.randomUUID().toString(), System.currentTimeMillis(),
                        event.getAdministratorId()));
    }
```

#### 9.4 컴파일 + commit

- [ ] **Step 6: 컴파일 + 회귀**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. 기존 `DomainEventRedisRelayTest`(있다면) 회귀 없음.

- [ ] **Step 7: commit**

```bash
git add \
  common/src/main/java/com/pfplaybackend/api/common/domain/enums/MessageTopic.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomTerminatedMessage.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomSuspendedMessage.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/in/listener/message/RoomRestoredMessage.java \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/event/DomainEventRedisRelay.java
git commit -m "$(cat <<'EOF'
feat(party): Redis fanout for admin partyroom events (PR 8)

Adds three new MessageTopic enum values + record-style message DTOs +
DomainEventRedisRelay listener methods for the admin-context events
introduced in G2:

- ROOM_TERMINATED ← PartyroomTerminatedEvent (administrator+reason payload)
- ROOM_SUSPENDED  ← PartyroomSuspendedEvent
- ROOM_RESTORED   ← PartyroomRestoredEvent

Pre-existing PARTYROOM_CLOSED topic (host-self-close) is unchanged —
clients subscribing to both topics see the same final state regardless
of who initiated the room closure.

All listeners are AFTER_COMMIT (no @Transactional — relay does not need
its own tx; matches existing relay convention).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 3: Bulk crew deactivate + AdminPartyroomCommandService + Audit Listener

**Goal of chunk:** Party `CrewRepository`에 bulk deactivate 메서드 추가 → Administration BC `AdminPartyroomCommandService`(terminate/suspend/restore/updateMeta/setDisplayFlag) 신설 → atomic audit listener `PartyroomAdminActionListener` 신설. 5개 endpoint(B-3~B-6)의 service 레이어 완성.

### Task 10: `CrewRepository.bulkDeactivateByPartyroomId` + IT

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepository.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepositoryAtomicToggleIT.java` — 신규 테스트 추가

#### 10.1 메서드 추가

- [ ] **Step 1:** 기존 `activateCrew`/`deactivateCrew` 뒤에 추가:

```java
    /**
     * 특정 partyroom의 모든 active crew를 일괄 inactive 전환. atomic single statement.
     * B-3 admin terminate 흐름에서 사용 — N개 crew를 1개 SQL로 처리.
     *
     *  - 반환: 영향 받은 row 수 (기존 active crew 수)
     *  - exitedAt 일괄 설정
     *  - 동시에 같은 룸에 enter 시도하는 crew는 별 race 영역
     *    (UNIQUE 제약 + B-3은 즉시 status=TERMINATED라 PartyroomEntrySpecification에서 거부됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CrewData c SET c.isActive = false, c.exitedAt = :now " +
           "WHERE c.partyroomId = :partyroomId AND c.isActive = true")
    int bulkDeactivateByPartyroomId(@Param("partyroomId") PartyroomId partyroomId,
                                    @Param("now") LocalDateTime now);
```

#### 10.2 IT 추가

- [ ] **Step 2:** 기존 `CrewRepositoryAtomicToggleIT`에 추가:

```java
    @Test
    @DisplayName("bulkDeactivateByPartyroomId — N active crew → 모두 inactive 전환")
    void bulk_deactivate_normal() {
        long roomId = 5001L;
        seedActiveCrew(roomId, 5001L);
        seedActiveCrew(roomId, 5002L);
        seedActiveCrew(roomId, 5003L);
        seedInactiveCrew(roomId, 5004L);   // 이미 inactive — 영향 없어야 함

        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(roomId), LocalDateTime.now());

        assertThat(affected).isEqualTo(3);   // active 3건만
        assertThat(crewRepository.findByPartyroomIdAndIsActiveTrue(new PartyroomId(roomId))).isEmpty();
    }

    @Test
    @DisplayName("bulkDeactivateByPartyroomId — 다른 룸의 crew는 영향 없음")
    void bulk_deactivate_room_isolation() {
        long roomA = 5010L;
        long roomB = 5011L;
        seedActiveCrew(roomA, 5010L);
        seedActiveCrew(roomB, 5011L);

        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(roomA), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        // roomB의 crew는 여전히 active
        assertThat(crewRepository.findByPartyroomIdAndIsActiveTrue(new PartyroomId(roomB))).hasSize(1);
    }

    @Test
    @DisplayName("bulkDeactivateByPartyroomId — 빈 룸 → 0 affected")
    void bulk_deactivate_empty() {
        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(99_999L), LocalDateTime.now());
        assertThat(affected).isZero();
    }
```

- [ ] **Step 3: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*CrewRepositoryAtomicToggleIT" 2>&1 | tail -20
```

Expected: 신규 3 + 기존 6 = 9 tests pass.

- [ ] **Step 4: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepository.java \
  app/src/test/java/com/pfplaybackend/api/party/adapter/out/persistence/CrewRepositoryAtomicToggleIT.java
git commit -m "$(cat <<'EOF'
feat(party): CrewRepository.bulkDeactivateByPartyroomId — atomic mass exit (PR 8)

Single SQL UPDATE flips is_active=false + sets exitedAt for all currently
active crew rows in a given partyroom. Used by AdminPartyroomCommandService.
terminate() to mass-exit a room in one statement instead of N per-crew
deactivateCrew calls.

ITs cover normal mass-deactivate, room isolation, and empty-room cases.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 11: `AdminPartyroomCommandService` — 5 메서드 (terminate/suspend/restore/setDisplayFlag/updateMeta)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomCommandService.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomCommandServiceTest.java`

#### 11.1 Service 작성

- [ ] **Step 1: 본체**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.event.PartyroomDisplayFlagChangedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomMetaUpdatedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomRestoredEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomSuspendedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin이 partyroom 상태/메타/displayFlag를 변경하는 5 use case.
 * Q1 결정 — Administration이 PartyroomAggregatePort 직접 호출 (use-case port 미도입).
 *
 * 모든 메서드는 @Transactional. 도메인 메서드 호출 → save → 이벤트 publish.
 * 같은 TX 안에서 PartyroomAdminActionListener가 audit row INSERT (Q2 atomic).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPartyroomCommandService {

    private final PartyroomAggregatePort aggregatePort;
    private final CrewRepository crewRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public void terminate(PartyroomId partyroomId, String reason, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        LocalDateTime now = LocalDateTime.now(clock);

        int crewsDeactivated = crewRepository.bulkDeactivateByPartyroomId(partyroomId, now);
        log.info("[AdminPartyroom.terminate] partyroomId={}, deactivatedCrews={}, by adminId={}",
                partyroomId.getId(), crewsDeactivated, administratorId);

        partyroom.terminate();   // PR 7 strict guard — TERMINATED 재진입 시 ILLEGAL_STATE_TRANSITION
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomTerminatedEvent(partyroomId, administratorId, reason)
        );
    }

    @Transactional
    public void suspend(PartyroomId partyroomId, String reason, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        partyroom.suspend();   // PR 7 strict guard — ACTIVE에서만
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomSuspendedEvent(partyroomId, administratorId, reason)
        );
        log.info("[AdminPartyroom.suspend] partyroomId={}, by adminId={}", partyroomId.getId(), administratorId);
    }

    @Transactional
    public void restore(PartyroomId partyroomId, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        partyroom.restore();   // PR 7 strict guard — SUSPENDED에서만
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomRestoredEvent(partyroomId, administratorId)
        );
        log.info("[AdminPartyroom.restore] partyroomId={}, by adminId={}", partyroomId.getId(), administratorId);
    }

    @Transactional
    public void setDisplayFlag(PartyroomId partyroomId, DisplayFlag newFlag, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        DisplayFlag oldFlag = partyroom.getDisplayFlag();

        switch (newFlag) {
            case FEATURED -> partyroom.setDisplayFlagFeatured();
            case HIDDEN   -> partyroom.setDisplayFlagHidden();
            case NORMAL   -> partyroom.setDisplayFlagNormal();
        }
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomDisplayFlagChangedEvent(partyroomId, administratorId, oldFlag, newFlag)
        );
        log.info("[AdminPartyroom.setDisplayFlag] partyroomId={}, {} → {}, by adminId={}",
                partyroomId.getId(), oldFlag, newFlag, administratorId);
    }

    /**
     * Meta 부분 수정. null 인자는 "변경 안 함". 최소 1개는 non-null 가정 (controller에서 검증).
     * diff는 mutation 전 캡쳐 (spec §4.5 step 2).
     */
    @Transactional
    public void updateMeta(PartyroomId partyroomId, String newTitle, String newIntroduction,
                           Integer newPlaybackTimeLimitMinutes, Long administratorId) {
        PartyroomData partyroom = loadPartyroom(partyroomId);
        // PR 7 PartyroomEntrySpecification는 SUSPENDED entry를 거부하지만, meta update는 status 무관 허용.
        // TERMINATED는 명시적으로 거부.
        if (partyroom.isTerminated()) {
            throw ExceptionCreator.create(PartyroomException.ALREADY_TERMINATED);
        }

        Map<String, Map<String, Object>> diff = new HashMap<>();

        if (newTitle != null && !newTitle.equals(partyroom.getTitle())) {
            diff.put("title", Map.of("old", partyroom.getTitle(), "new", newTitle));
        }
        if (newIntroduction != null && !newIntroduction.equals(partyroom.getIntroduction())) {
            diff.put("introduction", Map.of("old", partyroom.getIntroduction(), "new", newIntroduction));
        }
        // playbackTimeLimit은 VO — old는 minutes로 노출, compare는 minutes 정수
        Integer oldMinutes = partyroom.getPlaybackTimeLimit() == null
                ? null : (int) partyroom.getPlaybackTimeLimit().getMinutes();
        if (newPlaybackTimeLimitMinutes != null && !newPlaybackTimeLimitMinutes.equals(oldMinutes)) {
            diff.put("playbackTimeLimit",
                    Map.of("old", oldMinutes == null ? "null" : oldMinutes.toString(),
                           "new", newPlaybackTimeLimitMinutes.toString()));
        }

        if (diff.isEmpty()) {
            log.info("[AdminPartyroom.updateMeta] partyroomId={} — no actual changes, no event published",
                    partyroomId.getId());
            return;
        }

        partyroom.updateBaseInfo(
                newTitle != null ? newTitle : partyroom.getTitle(),
                newIntroduction != null ? newIntroduction : partyroom.getIntroduction(),
                partyroom.getLinkDomain(),
                newPlaybackTimeLimitMinutes != null
                        ? PlaybackTimeLimit.ofMinutes(newPlaybackTimeLimitMinutes)
                        : partyroom.getPlaybackTimeLimit()
        );
        aggregatePort.savePartyroom(partyroom);

        eventPublisher.publishEvent(
                new PartyroomMetaUpdatedEvent(partyroomId, administratorId, diff)
        );
        log.info("[AdminPartyroom.updateMeta] partyroomId={}, changedFields={}, by adminId={}",
                partyroomId.getId(), diff.keySet(), administratorId);
    }

    private PartyroomData loadPartyroom(PartyroomId partyroomId) {
        return aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
    }
}
```

⚠️ **`PlaybackTimeLimit.getMinutes()` 시그니처**: 정확한 메서드명/리턴 타입은 `app/src/main/java/com/pfplaybackend/api/party/domain/value/PlaybackTimeLimit.java` 확인. `getMinutes()` 없으면 `getValue()` 또는 `toMinutes()` 등 실제 API에 맞춰 조정.

#### 11.2 Service 단위 테스트

- [ ] **Step 2: 단위 테스트 작성**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.*;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.common.domain.value.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPartyroomCommandServiceTest {

    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private CrewRepository crewRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    private final Clock clock = Clock.fixed(LocalDateTime.of(2026, 4, 27, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @InjectMocks
    private AdminPartyroomCommandService service;

    private static final Long ADMIN_ID = 1L;
    private static final PartyroomId PID = new PartyroomId(100L);

    @BeforeEach
    void wireClock() {
        // Clock 직접 주입 (Mockito @InjectMocks가 final field 주입 못하면 대체로 reflection 또는 setup 필요)
        // 본 테스트는 Mockito 3.5+의 @InjectMocks가 final도 처리하므로 설정만으로 충분.
    }

    private PartyroomData activeRoom() {
        return PartyroomData.create(
                "Test", "intro", LinkDomain.of("link"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, new UserId(1L)
        );
    }

    @Test
    @DisplayName("terminate — bulk deactivate + status TERMINATED + 이벤트 publish")
    void terminate_happy() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));
        when(crewRepository.bulkDeactivateByPartyroomId(eq(PID), any())).thenReturn(5);

        service.terminate(PID, "violation", ADMIN_ID);

        verify(crewRepository).bulkDeactivateByPartyroomId(eq(PID), any());
        verify(aggregatePort).savePartyroom(p);
        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.TERMINATED);

        ArgumentCaptor<PartyroomTerminatedEvent> captor = ArgumentCaptor.forClass(PartyroomTerminatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("violation");
        assertThat(captor.getValue().getAdministratorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("terminate — 룸 없음 → NotFoundException")
    void terminate_not_found() {
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.terminate(PID, "x", ADMIN_ID))
                .isInstanceOf(NotFoundException.class);
        verify(crewRepository, never()).bulkDeactivateByPartyroomId(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("terminate — 이미 TERMINATED → ConflictException, audit publish 안 됨")
    void terminate_already_terminated() {
        PartyroomData p = activeRoom();
        p.terminate();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.terminate(PID, "x", ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verify(eventPublisher, never()).publishEvent(any(PartyroomTerminatedEvent.class));
    }

    @Test
    @DisplayName("suspend — ACTIVE → SUSPENDED + 이벤트")
    void suspend_happy() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.suspend(PID, "investigation", ADMIN_ID);

        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
        verify(eventPublisher).publishEvent(any(PartyroomSuspendedEvent.class));
    }

    @Test
    @DisplayName("restore — SUSPENDED → ACTIVE + 이벤트")
    void restore_happy() {
        PartyroomData p = activeRoom();
        p.suspend();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.restore(PID, ADMIN_ID);

        assertThat(p.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
        verify(eventPublisher).publishEvent(any(PartyroomRestoredEvent.class));
    }

    @Test
    @DisplayName("setDisplayFlag — FEATURED 설정 + 이벤트")
    void setDisplayFlag_featured() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.setDisplayFlag(PID, DisplayFlag.FEATURED, ADMIN_ID);

        assertThat(p.getDisplayFlag()).isEqualTo(DisplayFlag.FEATURED);

        ArgumentCaptor<PartyroomDisplayFlagChangedEvent> captor =
                ArgumentCaptor.forClass(PartyroomDisplayFlagChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getOldFlag()).isEqualTo(DisplayFlag.NORMAL);
        assertThat(captor.getValue().getNewFlag()).isEqualTo(DisplayFlag.FEATURED);
    }

    @Test
    @DisplayName("updateMeta — title 변경 → diff.title.{old,new} 포함 이벤트")
    void updateMeta_title() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.updateMeta(PID, "New Title", null, null, ADMIN_ID);

        assertThat(p.getTitle()).isEqualTo("New Title");

        ArgumentCaptor<PartyroomMetaUpdatedEvent> captor =
                ArgumentCaptor.forClass(PartyroomMetaUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getDiff()).containsKey("title");
        assertThat(captor.getValue().getDiff().get("title")).containsEntry("old", "Test").containsEntry("new", "New Title");
    }

    @Test
    @DisplayName("updateMeta — 모든 필드 동일 → 이벤트 publish 없음 (no-op)")
    void updateMeta_no_changes() {
        PartyroomData p = activeRoom();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        service.updateMeta(PID, "Test", "intro", 5, ADMIN_ID);

        verify(eventPublisher, never()).publishEvent(any(PartyroomMetaUpdatedEvent.class));
    }

    @Test
    @DisplayName("updateMeta — TERMINATED 룸 → ForbiddenException (ALREADY_TERMINATED)")
    void updateMeta_terminated() {
        PartyroomData p = activeRoom();
        p.terminate();
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.updateMeta(PID, "x", null, null, ADMIN_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
```

- [ ] **Step 3: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminPartyroomCommandServiceTest" 2>&1 | tail -20
```

Expected: 9 tests pass.

- [ ] **Step 4: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomCommandService.java \
  app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomCommandServiceTest.java
git commit -m "$(cat <<'EOF'
feat(administration): AdminPartyroomCommandService — 5 admin endpoints (PR 8)

5 use cases for admin partyroom management:
- terminate(partyroomId, reason, adminId): bulk-deactivate active crew,
  status=TERMINATED, publish PartyroomTerminatedEvent
- suspend(partyroomId, reason, adminId): status=SUSPENDED, event
- restore(partyroomId, adminId): status=ACTIVE, event
- setDisplayFlag(partyroomId, newFlag, adminId): switch on flag → 3
  domain methods, captures oldFlag for event, publish event
- updateMeta(partyroomId, title?, intro?, playbackLimit?, adminId):
  diff captured BEFORE mutation, no-op short-circuit when nothing
  changes, TERMINATED rejected; rest goes through partyroom.updateBaseInfo

All methods @Transactional. Events published from within tx so the
synchronous PartyroomAdminActionListener (next task) participates in
the same tx for atomic audit (Q2 spec decision).

Unit tests cover happy paths + state-guard rejections + diff capture +
no-op short-circuit + event payload assertions via ArgumentCaptor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 12: `PartyroomAdminActionListener` + IT (atomic audit)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListener.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerIT.java`

#### 12.1 Listener 작성

- [ ] **Step 1: 본체**

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.party.domain.event.PartyroomDisplayFlagChangedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomMetaUpdatedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomRestoredEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomSuspendedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Admin 액션의 atomic audit listener.
 *
 * - @EventListener (NOT @TransactionalEventListener) — synchronous + same TX
 * - listener INSERT 실패 시 ERROR + rethrow → caller TX rollback (Q2 atomic 보장)
 * - administratorId는 이벤트 페이로드에서 — SecurityContext 의존 없음
 *
 * PR 7 PartyroomCounterListener와 phase 다름 (의도적 분기):
 * counter는 side-effect (AFTER_COMMIT, swallow); audit는 parallel record (sync, rethrow).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartyroomAdminActionListener {

    private final PartyroomAdminActionRepository adminActionRepository;

    @EventListener
    public void on(PartyroomTerminatedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.TERMINATE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                event.getReason(),
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomSuspendedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.SUSPEND_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                event.getReason(),
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomRestoredEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.RESTORE_PARTYROOM,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.empty(),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomMetaUpdatedEvent event) {
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(),
                PartyroomAdminActionType.UPDATE_PARTYROOM_META,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.of(Map.of("changes", event.getDiff())),
                event.getOccurredAt()
        ));
    }

    @EventListener
    public void on(PartyroomDisplayFlagChangedEvent event) {
        PartyroomAdminActionType type = switch (event.getNewFlag()) {
            case FEATURED -> PartyroomAdminActionType.SET_FEATURED;
            case HIDDEN   -> PartyroomAdminActionType.SET_HIDDEN;
            case NORMAL   -> PartyroomAdminActionType.SET_NORMAL;
        };
        save(PartyroomAdminActionData.of(
                event.getAdministratorId(), type,
                AdminActionTargetType.PARTYROOM,
                event.getPartyroomId().getId(),
                event.getPartyroomId().getId(),
                null,
                JsonMetadata.of(Map.of(
                        "old_flag", event.getOldFlag().name(),
                        "new_flag", event.getNewFlag().name()
                )),
                event.getOccurredAt()
        ));
    }

    private void save(PartyroomAdminActionData action) {
        try {
            adminActionRepository.save(action);
        } catch (Exception e) {
            // listener가 같은 TX이므로 throw하면 caller도 rollback.
            // ERROR 로그 + rethrow — audit 무결성 우선.
            log.error("[PartyroomAdminActionListener] Failed to insert admin_action — caller TX will rollback. " +
                      "actionType={}, administratorId={}, partyroomId={}",
                      action.getActionType(), action.getAdministratorId(), action.getPartyroomId(), e);
            throw e;
        }
    }
}
```

#### 12.2 IT 작성

- [ ] **Step 2: end-to-end IT (atomic 보장 핵심 검증)**

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomCommandService;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomAdminActionListenerIT extends AbstractIntegrationTest {

    @Autowired private AdminPartyroomCommandService commandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomAdminActionRepository auditRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private Long superAdminId;
    private Long partyroomId;

    @BeforeEach
    void seed() {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(700L), "audit-listener-it@x", "h"));
        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(700L));
        this.superAdminId = superAdmin.getAdministratorId();

        PartyroomData p = PartyroomData.create(
                "audit-it", "intro", LinkDomain.of("link-audit"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(700L)
        );
        this.partyroomId = partyroomRepository.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("terminate → admin_action TERMINATE_PARTYROOM 1 row + status TERMINATED 동시 commit")
    void terminate_atomic_audit() {
        commandService.terminate(new PartyroomId(partyroomId), "violation", superAdminId);

        // partyroom row와 audit row 모두 commit됨
        PartyroomData reloaded = partyroomRepository.findById(partyroomId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PartyroomStatus.TERMINATED);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.TERMINATE_PARTYROOM);
        assertThat(audits.get(0).getReason()).isEqualTo("violation");
        assertThat(audits.get(0).getAdministratorId()).isEqualTo(superAdminId);
    }

    @Test
    @DisplayName("setDisplayFlag → SET_FEATURED action_type + metadata old/new")
    void setDisplayFlag_metadata() {
        commandService.setDisplayFlag(new PartyroomId(partyroomId), DisplayFlag.FEATURED, superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.SET_FEATURED);
        assertThat(audits.get(0).getMetadata().data())
                .containsEntry("old_flag", "NORMAL")
                .containsEntry("new_flag", "FEATURED");
    }

    @Test
    @DisplayName("updateMeta → UPDATE_PARTYROOM_META + metadata.changes 직렬화")
    void updateMeta_changes_metadata() {
        commandService.updateMeta(new PartyroomId(partyroomId), "New Title", null, null, superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.UPDATE_PARTYROOM_META);
        @SuppressWarnings("unchecked")
        var changes = (java.util.Map<String, Object>) audits.get(0).getMetadata().data().get("changes");
        assertThat(changes).containsKey("title");
    }

    @Test
    @DisplayName("suspend + restore — 2 audit rows 시간 역순 노출")
    void suspend_then_restore() {
        commandService.suspend(new PartyroomId(partyroomId), "investigation", superAdminId);
        commandService.restore(new PartyroomId(partyroomId), superAdminId);

        List<PartyroomAdminActionData> audits =
                auditRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId);
        assertThat(audits).hasSize(2);
        // 최신 first
        assertThat(audits.get(0).getActionType()).isEqualTo(PartyroomAdminActionType.RESTORE_PARTYROOM);
        assertThat(audits.get(1).getActionType()).isEqualTo(PartyroomAdminActionType.SUSPEND_PARTYROOM);
    }
}
```

- [ ] **Step 3: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*PartyroomAdminActionListenerIT" 2>&1 | tail -30
```

Expected: 4 IT pass.

- [ ] **Step 4: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListener.java \
  app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/PartyroomAdminActionListenerIT.java
git commit -m "$(cat <<'EOF'
feat(administration): PartyroomAdminActionListener — atomic audit (PR 8)

@EventListener (synchronous, same TX as publisher) — Q2 spec decision
for atomic audit guarantee. Listener INSERT failure rethrows → caller
TX rolls back → state and audit either both succeed or neither does.

Five handlers, one per new event:
- PartyroomTerminatedEvent → TERMINATE_PARTYROOM
- PartyroomSuspendedEvent  → SUSPEND_PARTYROOM
- PartyroomRestoredEvent   → RESTORE_PARTYROOM
- PartyroomMetaUpdatedEvent → UPDATE_PARTYROOM_META
                             with metadata.changes = diff
- PartyroomDisplayFlagChangedEvent → SET_FEATURED/HIDDEN/NORMAL
                             with metadata.{old_flag, new_flag}

Phase difference from PR 7 PartyroomCounterListener documented inline:
counter (AFTER_COMMIT, REQUIRES_NEW, swallow) vs audit (sync, REQUIRED,
rethrow). Both are correct for their use cases.

End-to-end ITs invoke AdminPartyroomCommandService → assert both partyroom
state mutation AND audit row land. Suspend+restore produces 2 ordered
audit rows. JsonMetadata.changes deserialization round-trip verified.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Chunk 4: Cross-BC list/detail repository + Query/Command Controllers + Bulk action

**Goal of chunk:** B-1/B-2 read path (cross-BC JOIN) + B-1~B-6/B-8 controllers + B-8 bulk service. PR 8 endpoint들이 client에 노출되는 단계.

### Task 13: `AdminPartyroomQueryRepository` + Impl (cross-BC JOIN) + IT

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminPartyroomQueryRepository.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminPartyroomQueryRepositoryImpl.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminPartyroomListFilter.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminPartyroomListRow.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminPartyroomQueryRepositoryImplIT.java`

본 task는 cross-BC JOIN의 핵심 — spec §7.2 entity-path notes 정확히 따라야 함:
- `qPartyroomData.hostId.uid` (host_id는 UserId embedded VO)
- `qUserAccountData.userId.uid` (PK는 @EmbeddedId UserId)
- `qMemberData.userAccountId` (plain Long FK column)
- `qMemberData.profileData.bio.nickname.value` (Member → ProfileData(@OneToOne) → Bio(@Embedded) → Nickname(@Embedded VO with `value`))

#### 13.1 DTO records

- [ ] **Step 1: `AdminPartyroomListFilter` record**

```java
package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

public record AdminPartyroomListFilter(
        PartyroomStatus status,        // null = default (<> TERMINATED, ACTIVE+SUSPENDED)
        StageType stageType,           // null = 전체
        LocalDateTime createdFrom,     // null = no lower bound
        LocalDateTime createdTo,       // null = no upper bound
        String hostQuery               // partial match on email/nickname; null = 안 함
) {}
```

- [ ] **Step 2: `AdminPartyroomListRow` record**

```java
package com.pfplaybackend.api.administration.application.dto;

import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

public record AdminPartyroomListRow(
        Long partyroomId,
        String title,
        StageType stageType,
        Long hostUserAccountId,
        String hostNickname,
        int crewCount,
        long djCount,
        Boolean playbackActivated,   // Boolean (not primitive) — left join이 null 가능
        PartyroomStatus status,
        DisplayFlag displayFlag,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt
) {}
```

(`hostEmail`은 list response에 노출 안 함 — privacy. detail에서 노출.)

#### 13.2 Repository interface

- [ ] **Step 3: interface**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminPartyroomQueryRepository {
    Page<AdminPartyroomListRow> findAdminList(AdminPartyroomListFilter filter, Pageable pageable);
}
```

#### 13.3 Repository impl (QueryDSL)

- [ ] **Step 4: impl**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.QDjData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.user.domain.entity.data.QMemberData;
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Admin 파티룸 list — cross-BC JOIN (partyroom + user_account + member).
 *
 * Spec §7.2 / §7.3 entity-path notes 따름:
 *   - host_id (Party UserId VO) ↔ user_account.user_id (IAM @EmbeddedId UserId VO)
 *   - member.user_account_id (plain Long FK)
 *   - nickname: m.profileData.bio.nickname.value (Member → ProfileData → Bio → Nickname)
 *
 * ArchUnit 가드(Task 18)가 본 cross-BC 의존성을 단방향으로 강제.
 */
@Repository
@RequiredArgsConstructor
public class AdminPartyroomQueryRepositoryImpl implements AdminPartyroomQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<AdminPartyroomListRow> findAdminList(AdminPartyroomListFilter filter, Pageable pageable) {
        QPartyroomData p = QPartyroomData.partyroomData;
        QUserAccountData ua = QUserAccountData.userAccountData;
        QMemberData m = QMemberData.memberData;
        QDjData dj = QDjData.djData;
        QPartyroomPlaybackData pb = QPartyroomPlaybackData.partyroomPlaybackData;

        var djCountAlias = Expressions.numberPath(Long.class, "djCount");
        var djCountSubquery = JPAExpressions
                .select(dj.count())
                .from(dj)
                .where(dj.partyroomId.id.eq(p.id));

        BooleanBuilder where = buildPredicates(filter, p, ua, m);

        JPAQuery<AdminPartyroomListRow> query = queryFactory
                .select(Projections.constructor(AdminPartyroomListRow.class,
                        p.id, p.title, p.stageType,
                        ua.userId.uid,
                        m.profileData.bio.nickname.value,
                        p.crewCount,
                        ExpressionUtils.as(djCountSubquery, djCountAlias),
                        pb.isActivated,
                        p.status, p.displayFlag,
                        p.createdAt, p.lastActivityAt
                ))
                .from(p)
                .leftJoin(ua).on(ua.userId.uid.eq(p.hostId.uid))
                .leftJoin(m).on(m.userAccountId.eq(ua.userId.uid))
                .leftJoin(pb).on(pb.partyroomId.id.eq(p.id))
                .where(where);

        applySort(query, pageable.getSort(), p, m);

        Long total = queryFactory
                .select(p.count())
                .from(p)
                .leftJoin(ua).on(ua.userId.uid.eq(p.hostId.uid))
                .leftJoin(m).on(m.userAccountId.eq(ua.userId.uid))
                .where(where)
                .fetchOne();

        List<AdminPartyroomListRow> content = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder buildPredicates(AdminPartyroomListFilter f, QPartyroomData p,
                                           QUserAccountData ua, QMemberData m) {
        BooleanBuilder b = new BooleanBuilder();
        if (f.status() != null) {
            b.and(p.status.eq(f.status()));
        } else {
            // default: <> TERMINATED (Risk #6 어드민 사용성 — ACTIVE + SUSPENDED 노출)
            b.and(p.status.ne(PartyroomStatus.TERMINATED));
        }
        if (f.stageType() != null) b.and(p.stageType.eq(f.stageType()));
        if (f.createdFrom() != null) b.and(p.createdAt.goe(f.createdFrom()));
        if (f.createdTo() != null) b.and(p.createdAt.lt(f.createdTo()));
        if (f.hostQuery() != null && !f.hostQuery().isBlank()) {
            String like = "%" + f.hostQuery() + "%";
            b.and(ua.email.like(like).or(m.profileData.bio.nickname.value.like(like)));
        }
        return b;
    }

    private void applySort(JPAQuery<?> query, Sort sort, QPartyroomData p, QMemberData m) {
        if (sort.isUnsorted()) {
            query.orderBy(p.createdAt.desc());   // default
            return;
        }
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = switch (order.getProperty()) {
                case "createdAt"      -> p.createdAt;
                case "lastActivityAt" -> p.lastActivityAt;
                case "crewCount"      -> p.crewCount;
                case "title"          -> p.title;
                case "hostNickname"   -> m.profileData.bio.nickname.value;
                default -> throw new IllegalArgumentException("Unsupported sort field: " + order.getProperty());
            };
            query.orderBy(order.isAscending() ? path.asc() : path.desc());
        }
    }
}
```

⚠️ **CRITICAL — Nickname QueryDSL 경로 보정 필요:**
- `Nickname`은 `@Convert(converter = NicknameConverter.class)` 적용된 VO (Bio.java:12). `@Embeddable`이 아니므로 Q-class에서 `m.profileData.bio.nickname`은 `Path<Nickname>` 단일 노드 — `.value` sub-path 접근 불가.
- **like / 정렬 처리 옵션:**
  - **(a) `m.profileData.bio.nickname` (Nickname VO)** 직접 비교 — QueryDSL이 Converter 통해 String 변환 처리. like는 정확하지 않을 수 있음.
  - **(b) `Expressions.stringTemplate("function('coalesce', {0}, '')", m.profileData.bio.nickname).like(like)`** — DB column 직접 SQL function.
  - **(c) `m.profileData.getBio().getNicknameValue()`** entity 메서드는 Q-class에서 안 됨.
- **현실적 fallback**: 일단 (a)로 시도 → 컴파일/동작 안 되면 (b)로 전환. 본 plan 코드는 placeholder로 (a) 표기 — 구현 시 검증 후 (b) 적용.

위 sort/filter 코드의 `m.profileData.bio.nickname.value` 부분도 `m.profileData.bio.nickname` (Path<Nickname>)로 변경 후 컴파일 결과로 (b) 전환 결정.

#### 13.4 Repository IT

- [ ] **Step 5: IT 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPartyroomQueryRepositoryImplIT extends AbstractIntegrationTest {

    @Autowired private AdminPartyroomQueryRepository repository;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private MemberRepository memberRepository;

    @BeforeEach
    void seed() {
        seedHost(701L, "alice@x", "Alice");
        seedHost(702L, "bob@x", "Bob");
        seedHost(703L, "carol@x", "Carol");

        partyroomRepository.saveAndFlush(PartyroomData.create(
                "Active Room", "intro", LinkDomain.of("link-701"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(701L)));

        PartyroomData susp = PartyroomData.create(
                "Suspended Room", "intro", LinkDomain.of("link-702"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(702L));
        susp.suspend();
        partyroomRepository.saveAndFlush(susp);

        PartyroomData term = PartyroomData.create(
                "Terminated Room", "intro", LinkDomain.of("link-703"),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL, new UserId(703L));
        term.terminate();
        partyroomRepository.saveAndFlush(term);
    }

    private void seedHost(long uid, String email, String nickname) {
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(uid), email, "h"));
        // member with nickname requires ProfileData seed — depends on existing factory pattern.
        // 실제 nickname seed 방법은 user 모듈 IT 패턴 참조 (MemberData.createForUserAccount + initializeProfile).
        // 본 테스트의 핵심은 cross-BC JOIN 결과 정확성이므로 nickname null도 허용.
        MemberData member = MemberData.createForUserAccount(uid);
        // initializeProfile + updateProfileBio(nickname, ...) 호출이 정확한 시그니처는 user 모듈 확인
        memberRepository.save(member);
    }

    @Test
    @DisplayName("default — status 미지정이면 ACTIVE + SUSPENDED, TERMINATED 제외")
    void default_excludes_terminated() {
        Page<AdminPartyroomListRow> page = repository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminPartyroomListRow::title)
                .contains("Active Room", "Suspended Room")
                .doesNotContain("Terminated Room");
    }

    @Test
    @DisplayName("status=ACTIVE — ACTIVE만 노출")
    void filter_active() {
        Page<AdminPartyroomListRow> page = repository.findAdminList(
                new AdminPartyroomListFilter(PartyroomStatus.ACTIVE, null, null, null, null),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminPartyroomListRow::title)
                .containsOnly("Active Room");
    }

    @Test
    @DisplayName("hostQuery — email partial match")
    void filter_host_email() {
        Page<AdminPartyroomListRow> page = repository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, "alice"),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).hostUserAccountId()).isEqualTo(701L);
    }

    @Test
    @DisplayName("sort by title asc")
    void sort_title() {
        Page<AdminPartyroomListRow> page = repository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "title")));

        assertThat(page.getContent()).extracting(AdminPartyroomListRow::title)
                .startsWith("Active Room", "Suspended Room");
    }

    @Test
    @DisplayName("페이징 — page 0 size 1")
    void paging() {
        Page<AdminPartyroomListRow> page = repository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);   // ACTIVE + SUSPENDED
    }
}
```

⚠️ **`MemberData.createForUserAccount` + `initializeProfile`** 호출이 정확히 어떻게 ProfileData/Bio/Nickname을 채우는지는 user 모듈 IT를 grep해서 답습 (`grep -rn "MemberData.createForUserAccount" app/src/test/`). nickname seed 부분이 깨지면 `seedHost`에서 ProfileData를 직접 생성해 `member.initializeProfile(profile)` 호출로 대체.

- [ ] **Step 6: IT 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "*AdminPartyroomQueryRepositoryImplIT" 2>&1 | tail -30
```

Expected: 5 IT pass.

만약 컴파일 에러: Q-class 자동 생성 필요 → `:app:compileJava` 먼저 실행 후 재시도. 그래도 안 되면 `m.profileData.bio.nickname.value` 경로를 build 후 생성된 `QMemberData`/`QProfileData`/`QBio`/`QNickname` 파일에서 정확한 path 확인.

- [ ] **Step 7: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminPartyroomQueryRepository.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminPartyroomQueryRepositoryImpl.java \
  app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminPartyroomListFilter.java \
  app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminPartyroomListRow.java \
  app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminPartyroomQueryRepositoryImplIT.java
git commit -m "$(cat <<'EOF'
feat(administration): cross-BC admin partyroom list query (PR 8)

AdminPartyroomQueryRepository (interface + QueryDSL impl) — joins
partyroom + user_account + member tables for B-1 list endpoint.
DTO projection (AdminPartyroomListRow) avoids exposing entities
across BC boundaries.

Filter: status (default <> TERMINATED → ACTIVE+SUSPENDED, Risk #6
admin usability), stageType, createdFrom/To, hostQuery (email OR
nickname partial match).

Sort whitelist: createdAt / lastActivityAt / crewCount / title /
hostNickname (cross-BC JOIN required for last). Unknown sort field
throws IllegalArgumentException.

Cross-BC schema coupling explicit in this BC (Administration =
ops integrator); ArchUnit guard (Task 18) enforces the dependency
direction is unidirectional.

ITs cover default-excludes-TERMINATED, status filter, hostQuery email
match, sort by title, paging.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 14: `AdminPartyroomQueryService` (list + detail) + 단위 테스트

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryService.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminPartyroomListItemResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminPartyroomDetailResponse.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryServiceTest.java`

#### 14.1 Response DTOs

- [ ] **Step 1: List item response (record)**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;

public record AdminPartyroomListItemResponse(
        Long partyroomId,
        String title,
        StageType stageType,
        Long hostUserAccountId,
        String hostNickname,
        int crewCount,
        long djCount,
        boolean playbackActivated,
        PartyroomStatus status,
        DisplayFlag displayFlag,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt
) {}
```

(`AdminPartyroomListRow`와 동일 shape — controller 노출용 별 record로 BC 분리.)

- [ ] **Step 2: Detail response — composite**

```java
package com.pfplaybackend.api.administration.adapter.in.web.payload.response;

import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.StageType;

import java.time.LocalDateTime;
import java.util.List;

public record AdminPartyroomDetailResponse(
        Long partyroomId,
        String title,
        PartyroomStatus status,
        DisplayFlag displayFlag,
        Long hostUserAccountId,
        String hostNickname,
        String hostEmail,
        int crewCount,
        LocalDateTime lastActivityAt,
        StageType stageType,
        PlaybackSummary playback,
        List<CrewSummary> crews,
        List<DjSummary> djQueue,
        List<PenaltySummary> recentPenalties,
        List<ReportSummary> recentReports,
        List<AdminActionSummary> recentAdminActions
) {
    public record PlaybackSummary(boolean activated, String currentTrackName, Long currentDjCrewId) {}
    public record CrewSummary(Long crewId, Long memberId, GradeType gradeType, String nickname, LocalDateTime enteredAt) {}
    public record DjSummary(Long djId, Long crewId, String playlistName, int orderNumber) {}
    public record PenaltySummary(Long id, Long crewId, PenaltyType penaltyType, String punisherType, String reason, LocalDateTime date) {}
    public record ReportSummary(Long id, String category, String status, Long reporterUserAccountId, LocalDateTime createdAt) {}
    public record AdminActionSummary(Long actionId, PartyroomAdminActionType actionType, Long administratorId, LocalDateTime occurredAt) {}
}
```

⚠️ **`PenaltyType` import 정확히 확인** — 위치는 `com.pfplaybackend.api.party.domain.enums.PenaltyType` (대략). 컴파일 시 IDE 보정 필요.

#### 14.2 Service

- [ ] **Step 3: Service 본체**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.PartyroomAdminActionRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPartyroomQueryService {

    private final AdminPartyroomQueryRepository adminPartyroomQueryRepository;
    private final PartyroomAggregatePort aggregatePort;
    private final UserAccountRepository userAccountRepository;
    private final MemberRepository memberRepository;
    private final PartyroomAdminActionRepository adminActionRepository;
    // PR 9 V8 컬럼 도입 후 사용 — 일단 미주입
    // private final CrewPenaltyHistoryRepository crewPenaltyHistoryRepository;

    public Page<AdminPartyroomListItemResponse> list(AdminPartyroomListFilter filter, Pageable pageable) {
        return adminPartyroomQueryRepository.findAdminList(filter, pageable)
                .map(this::toListItem);
    }

    public AdminPartyroomDetailResponse detail(PartyroomId partyroomId) {
        // 1. partyroom 단건 (cross-BC join 단건 — 단순화: aggregatePort + 별 host fetch)
        PartyroomData partyroom = aggregatePort.findPartyroomById(partyroomId.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));

        // 2. host 정보 (email + nickname)
        // UserAccountRepository PK는 @EmbeddedId UserId — findById(UserId) 호출
        UserAccountData hostUa = userAccountRepository.findById(partyroom.getHostId()).orElse(null);
        MemberData hostMember = (hostUa == null) ? null
                : memberRepository.findByUserAccountId(hostUa.getUserId().getUid()).orElse(null);

        // 3. 활성 crew + nickname (bulk member fetch)
        List<CrewData> activeCrews = aggregatePort.findActiveCrews(partyroomId);
        Set<Long> crewUserIds = activeCrews.stream()
                .map(c -> c.getUserId().getUid())
                .collect(toSet());
        Map<Long, MemberData> memberByUid = crewUserIds.isEmpty() ? Map.of()
                : memberRepository.findAllByUserAccountIdIn(crewUserIds.stream().toList())
                        .stream().collect(toMap(MemberData::getUserAccountId, Function.identity()));

        List<AdminPartyroomDetailResponse.CrewSummary> crews = activeCrews.stream()
                .map(c -> {
                    MemberData m = memberByUid.get(c.getUserId().getUid());
                    return new AdminPartyroomDetailResponse.CrewSummary(
                            c.getId(),
                            m == null ? null : m.getMemberId(),
                            c.getGradeType(),
                            (m == null || m.getProfileData() == null)
                                    ? null : m.getProfileData().getNicknameValue(),
                            c.getEnteredAt()
                    );
                }).toList();

        // 4. DJ queue — 단순화: PartyroomAggregatePort.findDjsOrdered + 빈 playlistName
        List<AdminPartyroomDetailResponse.DjSummary> djQueue = aggregatePort.findDjsOrdered(partyroomId).stream()
                .map(d -> new AdminPartyroomDetailResponse.DjSummary(
                        d.getId(), d.getCrewId().getId(),
                        null,   // playlistName: PR 별도에서 PlaylistQueryPort 통해 채움 — PR 8 MVP는 null
                        d.getOrderNumber()))
                .toList();

        // 5. recentPenalties — PR 9 V8 컬럼 미존재. PR 8 MVP는 빈 배열.
        // (실제로 crew_penalty_history 테이블 자체는 존재하므로 데이터 fetch는 가능하지만,
        //  punisher_type 컬럼이 PR 9에 추가되므로 본 PR 8에선 빈 배열로 시작 — spec §2.2 결정.)
        List<AdminPartyroomDetailResponse.PenaltySummary> penalties = List.of();

        // 6. recentReports — PR 13 미구현, 빈 배열
        List<AdminPartyroomDetailResponse.ReportSummary> reports = List.of();

        // 7. recentAdminActions
        List<AdminPartyroomDetailResponse.AdminActionSummary> adminActions =
                adminActionRepository.findTop10ByPartyroomIdOrderByOccurredAtDesc(partyroomId.getId()).stream()
                        .map(a -> new AdminPartyroomDetailResponse.AdminActionSummary(
                                a.getId(), a.getActionType(), a.getAdministratorId(), a.getOccurredAt()))
                        .toList();

        // 8. playback summary
        var playbackState = aggregatePort.findPlaybackState(partyroomId);
        var playbackSummary = new AdminPartyroomDetailResponse.PlaybackSummary(
                playbackState.isActivated(),
                null,   // currentTrackName: PlaybackQueryPort 별도 호출 — MVP는 null
                playbackState.getCurrentDjCrewId() == null ? null : playbackState.getCurrentDjCrewId().getId()
        );

        return new AdminPartyroomDetailResponse(
                partyroom.getId(), partyroom.getTitle(), partyroom.getStatus(), partyroom.getDisplayFlag(),
                hostUa == null ? null : hostUa.getUserId().getUid(),
                (hostMember == null || hostMember.getProfileData() == null)
                        ? null : hostMember.getProfileData().getNicknameValue(),
                hostUa == null ? null : hostUa.getEmail(),
                partyroom.getCrewCount(), partyroom.getLastActivityAt(),
                partyroom.getStageType(),
                playbackSummary,
                crews, djQueue, penalties, reports, adminActions
        );
    }

    private AdminPartyroomListItemResponse toListItem(AdminPartyroomListRow row) {
        return new AdminPartyroomListItemResponse(
                row.partyroomId(), row.title(), row.stageType(),
                row.hostUserAccountId(), row.hostNickname(),
                row.crewCount(), row.djCount(), row.playbackActivated(),
                row.status(), row.displayFlag(),
                row.createdAt(), row.lastActivityAt()
        );
    }
}
```

⚠️ **playbackState API 시그니처 확인**: `aggregatePort.findPlaybackState(partyroomId)` 반환값이 `PartyroomPlaybackData`인지, `getCurrentDjCrewId()` 메서드가 있는지 정확 확인. `currentTrack` 컬럼 접근법도 동일 — 없으면 null로 처리.

#### 14.3 단위 테스트

- [ ] **Step 4: 단위 테스트** (간소화 — list passthrough + detail 조립 검증)

```java
package com.pfplaybackend.api.administration.application.service;

// (생략 — 단위 테스트는 Mock 기반: list는 repository.map → response 변환만 검증, detail은
//  전 sub-query를 mock해 빈 결과 / 일부 nickname null 등 edge case 검증.)
```

테스트는 너무 복잡하므로 본 plan에선 sketch만. 실제 작성 시 controller 통합 IT (Task 16)에서 end-to-end 검증으로 대체 가능.

- [ ] **Step 5: 컴파일 + commit**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :app:compileTestJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (단위 테스트 작성 후).

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomQueryService.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminPartyroomListItemResponse.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/AdminPartyroomDetailResponse.java
git commit -m "feat(administration): AdminPartyroomQueryService — list + composite detail (PR 8)

- list(): delegates to AdminPartyroomQueryRepository, maps to response DTO.
- detail(): composes 5-6 sub-queries (partyroom + host(UA+Member) + active
  crews + bulk member fetch for nicknames + dj queue + admin_action top 10).
  recentReports + recentPenalties hardcoded empty (PR 13 / PR 9 deferred).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 15: `AdminPartyroomCommandController` + WebMvcTest

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomCommandController.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/TerminatePartyroomRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/SuspendPartyroomRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdatePartyroomMetaRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdateDisplayFlagRequest.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomCommandControllerTest.java`

#### 15.1 Request DTOs (간단)

- [ ] **Step 1**: 4개 record/class — Bean Validation 적용

```java
// TerminatePartyroomRequest
public record TerminatePartyroomRequest(
        @NotBlank @Size(max = 500) String reason
) {}

// SuspendPartyroomRequest — 동일
public record SuspendPartyroomRequest(
        @NotBlank @Size(max = 500) String reason
) {}

// UpdatePartyroomMetaRequest — 모두 optional, 최소 1개 필요 (서비스에서 검증 또는 @AssertTrue)
public record UpdatePartyroomMetaRequest(
        @Size(max = 100) String title,
        @Size(max = 500) String introduction,
        @Min(1) @Max(60) Integer playbackTimeLimit
) {
    @JsonIgnore @AssertTrue(message = "최소 1개 필드는 변경 필요")
    public boolean isAtLeastOnePresent() {
        return title != null || introduction != null || playbackTimeLimit != null;
    }
}

// UpdateDisplayFlagRequest
public record UpdateDisplayFlagRequest(
        @NotNull DisplayFlag flag
) {}
```

#### 15.2 Controller

- [ ] **Step 2:**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.SuspendPartyroomRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.TerminatePartyroomRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.UpdateDisplayFlagRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.request.UpdatePartyroomMetaRequest;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.application.service.AdminPartyroomCommandService;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin Partyroom Commands API", description = "B-3~B-6 어드민 파티룸 상태/메타 변경")
@RestController
@RequestMapping("/api/v1/admin/partyrooms")
@RequiredArgsConstructor
public class AdminPartyroomCommandController {

    private final AdminPartyroomCommandService commandService;
    private final AdminContext adminContext;

    @Operation(summary = "B-3 룸 강제 종료")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/terminate")
    public ResponseEntity<Void> terminate(@PathVariable Long partyroomId,
                                          @Valid @RequestBody TerminatePartyroomRequest req) {
        commandService.terminate(new PartyroomId(partyroomId), req.reason(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-4 룸 일시 정지")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable Long partyroomId,
                                        @Valid @RequestBody SuspendPartyroomRequest req) {
        commandService.suspend(new PartyroomId(partyroomId), req.reason(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-4 룸 재개")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PostMapping("/{partyroomId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long partyroomId) {
        commandService.restore(new PartyroomId(partyroomId), adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-5 룸 메타데이터 수정")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{partyroomId}")
    public ResponseEntity<Void> updateMeta(@PathVariable Long partyroomId,
                                           @Valid @RequestBody UpdatePartyroomMetaRequest req) {
        commandService.updateMeta(new PartyroomId(partyroomId),
                req.title(), req.introduction(), req.playbackTimeLimit(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "B-6 Display flag 변경")
    @PreAuthorize("@adminAuth.isAdmin()")
    @PatchMapping("/{partyroomId}/display-flag")
    public ResponseEntity<Void> setDisplayFlag(@PathVariable Long partyroomId,
                                               @Valid @RequestBody UpdateDisplayFlagRequest req) {
        commandService.setDisplayFlag(new PartyroomId(partyroomId), req.flag(),
                adminContext.currentAdministratorId());
        return ResponseEntity.noContent().build();
    }
}
```

#### 15.3 WebMvcTest

- [ ] **Step 3:** 각 endpoint × (정상 / 권한 없음 / validation 실패 / 도메인 예외 매핑) — 단위 테스트는 PR 6 `AbstractAdminWebMvcTest` 패턴 답습. 본 plan에선 sketch.

(실제 코드는 PR 6 controller 테스트 참조 — `AbstractAdminWebMvcTest` 같은 base class 활용해 csrf, security, mockBean 설정.)

- [ ] **Step 4: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomCommandController.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/TerminatePartyroomRequest.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/SuspendPartyroomRequest.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdatePartyroomMetaRequest.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/UpdateDisplayFlagRequest.java \
  app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomCommandControllerTest.java
git commit -m "feat(administration): AdminPartyroomCommandController + 4 request DTOs (PR 8)

5 admin endpoints (B-3 terminate, B-4 suspend/restore, B-5 update meta,
B-6 display flag). All gated by @adminAuth.isAdmin(). All return 204.

Request DTOs use Bean Validation (@NotBlank, @Size, @Min, @Max).
UpdatePartyroomMetaRequest enforces 'at least one field' via @AssertTrue.

WebMvcTest covers normal/auth/validation/domain-exception paths per
endpoint, mirroring PR 6 AdministratorManagementController test layout.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 16: `AdminPartyroomQueryController` + WebMvcTest

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomQueryController.java`
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomQueryControllerTest.java`

- [ ] **Step 1: Controller**

```java
@Tag(name = "Admin Partyroom Queries API", description = "B-1 list / B-2 detail")
@RestController
@RequestMapping("/api/v1/admin/partyrooms")
@RequiredArgsConstructor
public class AdminPartyroomQueryController {

    private final AdminPartyroomQueryService queryService;

    @Operation(summary = "B-1 룸 목록 (페이징/필터/정렬)")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<Page<AdminPartyroomListItemResponse>> list(
            @RequestParam(required = false) PartyroomStatus status,
            @RequestParam(required = false) StageType stageType,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String host,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        // size 200 cap (admin DOS 방어)
        Pageable bounded = pageable.getPageSize() > 200
                ? PageRequest.of(pageable.getPageNumber(), 200, pageable.getSort())
                : pageable;
        return ResponseEntity.ok(queryService.list(
                new AdminPartyroomListFilter(status, stageType, createdFrom, createdTo, host),
                bounded));
    }

    @Operation(summary = "B-2 룸 상세")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{partyroomId}")
    public ResponseEntity<AdminPartyroomDetailResponse> detail(@PathVariable Long partyroomId) {
        return ResponseEntity.ok(queryService.detail(new PartyroomId(partyroomId)));
    }
}
```

- [ ] **Step 2: WebMvcTest sketch + commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomQueryController.java \
  app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomQueryControllerTest.java
git commit -m "feat(administration): AdminPartyroomQueryController — B-1 list + B-2 detail (PR 8)

list endpoint: @PageableDefault(size=50, sort=createdAt,desc), size cap
at 200. status/stageType/createdRange/host query params optional.
detail endpoint: single id path. Both gated @adminAuth.isAdmin().

WebMvcTest covers paging defaults, sort whitelist, 404 on missing id,
auth gating.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 17: B-8 bulk action — `AdminBulkPartyroomActionService` + `AdminPartyroomTransactionalUnit` + IT

**Files:**
- Create: `AdminBulkPartyroomActionService.java`
- Create: `AdminPartyroomTransactionalUnit.java`
- Create: `BulkPartyroomActionRequest.java` (request DTO)
- Create: `BulkPartyroomActionResponse.java` (response DTO with results)
- Modify: `AdminPartyroomCommandController` — `bulk-action` endpoint 추가
- Create: `AdminBulkPartyroomActionServiceTest.java` + `AdminBulkPartyroomActionIT.java`

핵심: per-item TX 분리 — outer service는 non-transactional, inner unit은 별 bean으로 `@Transactional`.

- [ ] **Step 1: `BulkActionType` enum + DTOs**

```java
public enum BulkActionType { TERMINATE, SUSPEND, SET_HIDDEN }

public record BulkPartyroomActionRequest(
        @NotEmpty @Size(min = 1, max = 100) List<Long> partyroomIds,
        @NotNull BulkActionType action,
        @NotBlank @Size(max = 500) String reason,
        Boolean skipErrors   // null이면 default true. @JsonProperty(defaultValue) Jackson 메타데이터일 뿐 — 직접 null check.
) {
    /** null-safe accessor — default true (skip and continue on errors). */
    public boolean skipErrorsOrDefault() {
        return skipErrors == null || skipErrors;
    }
}

public record BulkPartyroomActionResponse(List<BulkActionResult> results) {
    public record BulkActionResult(Long partyroomId, boolean success, String error) {}
}
```

- [ ] **Step 2: `AdminPartyroomTransactionalUnit` — 별 bean (Spring AOP self-invocation 회피)**

```java
@Service
@RequiredArgsConstructor
public class AdminPartyroomTransactionalUnit {
    private final AdminPartyroomCommandService commandService;

    @Transactional   // proxy를 통해 outer가 호출 → 새 TX 시작
    public void executeOne(PartyroomId partyroomId, BulkActionType action, String reason, Long administratorId) {
        switch (action) {
            case TERMINATE -> commandService.terminate(partyroomId, reason, administratorId);
            case SUSPEND   -> commandService.suspend(partyroomId, reason, administratorId);
            case SET_HIDDEN -> commandService.setDisplayFlag(partyroomId, DisplayFlag.HIDDEN, administratorId);
        }
    }
}
```

- [ ] **Step 3: `AdminBulkPartyroomActionService` — outer (non-tx)**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBulkPartyroomActionService {

    private final AdminPartyroomTransactionalUnit txUnit;

    public BulkPartyroomActionResponse execute(BulkPartyroomActionRequest req, Long administratorId) {
        boolean skipErrors = req.skipErrorsOrDefault();
        List<BulkPartyroomActionResponse.BulkActionResult> results = new ArrayList<>();
        for (Long pid : req.partyroomIds()) {
            try {
                txUnit.executeOne(new PartyroomId(pid), req.action(), req.reason(), administratorId);
                results.add(new BulkPartyroomActionResponse.BulkActionResult(pid, true, null));
            } catch (Exception e) {
                // production 예외는 ExceptionCreator.create()가 throw한 AbstractHTTPException 서브클래스
                // (DomainException은 enum 인터페이스이지 throwable 아님 — instanceof 체크 무의미)
                String errMsg = (e instanceof AbstractHTTPException he) ? he.getMessage() : "INTERNAL_ERROR";
                results.add(new BulkPartyroomActionResponse.BulkActionResult(pid, false, errMsg));
                log.warn("[bulk-action] failed partyroomId={}, action={}, error={}", pid, req.action(), errMsg);
                if (!skipErrors) break;   // strict: 첫 실패에서 중단
            }
        }
        return new BulkPartyroomActionResponse(results);
    }
}
```

- [ ] **Step 4: Controller 메서드 추가** (B-8 endpoint를 `AdminPartyroomCommandController`에)

```java
@Operation(summary = "B-8 일괄 액션")
@PreAuthorize("@adminAuth.isAdmin()")
@PostMapping("/bulk-action")
public ResponseEntity<BulkPartyroomActionResponse> bulkAction(
        @Valid @RequestBody BulkPartyroomActionRequest req) {
    return ResponseEntity.ok(bulkActionService.execute(req, adminContext.currentAdministratorId()));
}
```

(컨트롤러 필드에 `AdminBulkPartyroomActionService bulkActionService` 추가.)

- [ ] **Step 5: IT — per-item TX 동작 검증**

핵심 케이스:
- 3개 partyroomIds, 1개는 이미 TERMINATED → skipErrors=true: 나머지 2개 success, 그 1개 error msg
- skipErrors=false: 첫 실패에서 break, 후속 시도 안 함

- [ ] **Step 6: commit**

```bash
git add \
  app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminBulkPartyroomActionService.java \
  app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminPartyroomTransactionalUnit.java \
  app/src/main/java/com/pfplaybackend/api/administration/domain/enums/BulkActionType.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/request/BulkPartyroomActionRequest.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/payload/response/BulkPartyroomActionResponse.java \
  app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminPartyroomCommandController.java \
  app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminBulkPartyroomActionServiceTest.java \
  app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminBulkPartyroomActionIT.java
git commit -m "feat(administration): B-8 bulk action — per-item TX, skipErrors mode (PR 8)

AdminBulkPartyroomActionService (outer, non-transactional) iterates
through partyroomIds. Each item runs in its own TX via the separate
AdminPartyroomTransactionalUnit bean (Spring AOP self-invocation
workaround per PR 7 sample).

Per-item failures: skipErrors=true continues, false breaks. Successful
items remain committed regardless. Audit listener fires per-item from
within each item's tx → audit gap impossible.

DTOs: BulkPartyroomActionRequest (1-100 ids, NotBlank reason, default
skipErrors=true), BulkPartyroomActionResponse with per-item results.

ITs: 3 ids with one TERMINATED → skipErrors=true completes 2 of 3
successfully; skipErrors=false breaks at first failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

---

## Chunk 5: ArchUnit guards + spec catch-up + final verification

### Task 18: `CrossContextDependencyTest` ArchUnit 가드

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/architecture/CrossContextDependencyTest.java`

- [ ] **Step 1: 4 rules 작성**

```java
package com.pfplaybackend.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

@DisplayName("Cross-BC 의존성 단방향 가드 (PR 8 admin → 다른 BC만 허용)")
class CrossContextDependencyTest {

    static JavaClasses allClasses;

    @BeforeAll
    static void setUp() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.pfplaybackend.api");
    }

    @Test
    @DisplayName("Party 모듈은 user/auth/administration 참조 금지")
    void partyMustNotReferenceOthers() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.party..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.user.domain..",
                        "com.pfplaybackend.api.auth..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    @Test
    @DisplayName("User 모듈은 party/administration 참조 금지")
    void userMustNotReferenceOthers() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.user..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.party.domain..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    @Test
    @DisplayName("Auth 모듈은 party/administration 참조 금지")
    void authMustNotReferenceOthers() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pfplaybackend.api.auth..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pfplaybackend.api.party..",
                        "com.pfplaybackend.api.administration.."
                );
        rule.check(allClasses);
    }

    // Administration → 다른 BC는 허용 (cross-BC integrator). 단 entity setter 직접 호출 금지.
    // (suspend/restore/terminate/setDisplayFlagFeatured 같은 도메인 메서드는 허용)
}
```

⚠️ **주의:** 본 가드는 PR 7 setup 후 신규 PR 8 코드에 의해 violated될 가능성 높음 — 본 task는 PR 8의 모든 cross-BC read를 Administration BC 안에 넣은 후 마지막에 추가. 만약 다른 BC가 cross-reference하고 있다면 Plan에 누락된 violation 사이트 — 발견 시 task 추가하여 수정.

- [ ] **Step 2: 테스트 실행**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*CrossContextDependencyTest" 2>&1 | tail -30
```

Expected: 3 tests pass.

만약 violation 발견:
- Party 모듈에 user_account 참조 → PR 8 task에서 administration으로 옮긴다고 가정한 코드가 실제론 PR 7 이전에 있을 수 있음. grep으로 위치 식별, 별 cleanup task 추가
- ArchUnit rule 일부를 임시 약화시키지 말 것 — 위반 사이트 fix가 정답

- [ ] **Step 3: commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/architecture/CrossContextDependencyTest.java
git commit -m "test(arch): cross-BC unidirectional dependency guard (PR 8)

3 ArchUnit rules:
- Party 모듈 ↛ User/Auth/Administration
- User 모듈 ↛ Party/Administration
- Auth 모듈 ↛ Party/Administration

Administration → 다른 BC는 허용 (Q5 결정 — Administration이 ops view
integrator 역할). 단 spec §7.3 의도대로 admin이 entity setter 직접
호출은 금지 — 이미 PartyroomData에 setter 없음 + 도메인 메서드만
노출이라 자연 보장.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Task 19: spec 문서 catch-up

**File:** `docs/superpowers/specs/2026-04-27-admin-platform-pr8-design.md`

PR 8 구현 중 발견된 deviation/clarification 반영. 예:
- Q5 cross-BC JOIN의 정확한 entity path (이미 spec에 추가됨, 추가 변경 미필요)
- B-2 detail의 playbackSummary `currentTrackName`/`currentDjCrewId` 매핑 details
- BulkActionType enum 위치
- 실제 PR 8 commit 시퀀스 노트

- [ ] **Step 1: spec에 "Implementation reality" 섹션 추가 또는 §13 risk 표 행 strikeout**

세부 변경은 구현 결과에 따라. 최소 추가:
```markdown
> **PR 8 implementation note:** B-2 detail의 `playback.currentTrack` 매핑은 PR 8 MVP에선 null. PlaybackQueryPort 별도 호출은 follow-up.
```

- [ ] **Step 2: commit**

```bash
git add docs/superpowers/specs/2026-04-27-admin-platform-pr8-design.md
git commit -m "docs(spec): catch up PR 8 design to implementation reality

- B-2 detail: currentTrack mapping deferred to follow-up
- ...

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
"
```

### Final verification — plan 끝, 머지 전 체크

- [ ] **Step 1: 전체 회귀 테스트**

```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: PR 8 commit count 확인**

```bash
git log --oneline 8d3f4b5a..HEAD
```

Expected commit 시퀀스 (대략 14-16개):
1. spec (`94e35915`)
2. plan (이번)
3. G1: V7 + entity + enums + JsonMetadata (G1 묶음)
4. G2: 5 events + setDisplayFlag* (G2 묶음)
5. counter listener + reset
6. redis relay 확장
7. crew bulk deactivate
8. AdminPartyroomCommandService
9. PartyroomAdminActionListener + IT
10. AdminPartyroomQueryRepository + IT
11. AdminPartyroomQueryService + DTOs
12. AdminPartyroomCommandController + DTOs
13. AdminPartyroomQueryController
14. Bulk action service + controller
15. ArchUnit 가드
16. spec catch-up

- [ ] **Step 3: spec § acceptance — 7 endpoint 모두 작동 검증** (수동 또는 통합 IT)

수동: Postman/curl로 7 endpoint 호출 → 정상 200/204 응답 + DB의 partyroom 상태 + admin_action row 확인.
또는 별 e2e IT (Task 17 커버 가능).

- [ ] **Step 4: superpowers:finishing-a-development-branch 스킬로 머지 결정**

PR 머지 / PR 생성 / 추가 작업 등 마무리 옵션 결정.

---

## Known follow-ups (plan reviewer 어드바이저리, non-blocking)

본 plan 작성 중 plan-document-reviewer가 지적한 비-차단 사항들. 구현 중 마주치면 처리:

1. **Task 11 service** — `PlaybackTimeLimit.getMinutes()`이 `int` 반환이면 `(int)` cast 제거 가능 (cosmetic).
2. **Task 13 IT** — `seedHost`가 ProfileData/Bio/Nickname 초기화 안 하므로 host nickname 관련 assertion은 null 처리. 실제 nickname seed는 user 모듈의 `MemberData.initializeProfile(...)` + `updateProfileBio(...)` 호출 패턴 grep 후 정확히 적용 권장.
3. **Task 14** — 단위 테스트가 sketch 수준. 최소 happy path + null host + empty crews 3개는 작성 권장 (controller IT만으로는 detail 조립 로직 회귀 위험).
4. **Task 15/16 WebMvcTest** — sketch 수준. PR 6 `AbstractAdminWebMvcTest` 답습해 각 endpoint × 4 케이스(정상/auth/validation/도메인 예외) 보강 필요. 각 controller당 ~20 테스트 메서드 예상.
5. **Task 16 sort whitelist 외 필드** — repository에서 `IllegalArgumentException` throw → `@RestControllerAdvice`에 매핑 없으면 500. 400으로 매핑하거나 controller-side whitelist로 사전 검증 권장.
6. **Task 18 ArchUnit pre-check** — PR 8 시작 전 본 task의 rules를 현재 codebase에 적용해 pre-existing violation 목록 확보. legacy `..admin..` 패키지(데모/시뮬레이션용)는 cross-BC 가드에서 명시적으로 제외하거나 별 cleanup task 추가.
7. **Task 12 IT — `AdministratorData.createSuperAdmin(700L)`** factory 존재 확인 (PR 6 commit 참조). 없으면 builder 직접 사용.

---

**End of plan.**

