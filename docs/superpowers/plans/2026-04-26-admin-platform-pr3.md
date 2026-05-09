# PR 3: V9 system_config + 유지보수 모드 Filter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the `SystemConfig` aggregate (Operations BC) via Flyway V9, add an in-memory TTL cache for runtime config lookups, and ship a `MaintenanceModeFilter` that returns HTTP 503 for non-admin traffic when `maintenance.enabled=true`.

**Architecture:** V9 DDL creates `system_config` (key-value VARCHAR PK) and seeds two rows: `maintenance.enabled=false` and `maintenance.message`. A new `Operations` BC scaffold lives at `app/.../operations/...` (first usage). `SystemConfigCache` reads through `SystemConfigRepository` with a 30-second snapshot TTL — no `SystemConfigUpdated` event publishing yet (toggle happens via direct DB UPDATE until admin endpoints land in PR 6). `MaintenanceModeFilter` is an `OncePerRequestFilter` registered with `FilterRegistrationBean` at `Ordered.HIGHEST_PRECEDENCE` so it runs before the Spring Security filter chain and bypasses `/api/v1/admin/**` and `/actuator/health` regardless of mode.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring Data JPA, MySQL 8.0, Flyway, Lombok, Servlet Filter (OncePerRequestFilter), JUnit 5, Mockito, Testcontainers MySQL.

**Spec sources (read once, applied throughout):**
- `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.6 (V9 DDL + 애플리케이션 연계)
- `docs/superpowers/specs/2026-04-19-admin-platform-design.md` §3.1 (BC table — Operations row), §3.3.4 (`SystemConfig` aggregate)
- `docs/superpowers/specs/2026-04-19-admin-platform-features.md` §6.E-1 (유지보수 모드 API/filter behavior)
- `docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md` §9.1 PR 3 row + §9.3 cache stale row

**Lessons applied from PR 1 + PR 2:**
- Mechanical implementation tasks → `sonnet` model. Architecture-touching tasks (filter registration, BC scaffold) → `opus`.
- Reviewers default to `sonnet`; escalate to `opus` for filter ordering / security-adjacent work.
- Hibernate-vs-MySQL ENUM mismatch lesson: `system_config` has no ENUM column (all VARCHAR/TEXT) — no `columnDefinition` defensive pattern needed.
- Plan must explicitly preserve existing behavior; pre-PR-3 endpoints under `/api/v1/admin/**` MUST still return 401/403 (not 503) when maintenance mode is on.

**Branching:** Continue on `feature/admin-auth-iam-schema`. Each task ends in its own commit. PR 2 HEAD: `b8e8138f`; PR 3 builds on top.

**Out of scope (deferred):**
- Admin endpoints `GET/PATCH /api/v1/admin/system/config/maintenance` — toggling for PR 3 is via direct DB UPDATE in the smoke test. Endpoints arrive with admin CRUD in PR 6 along with `@PreAuthorize("hasRole('SUPER_ADMIN')")` once the central `adminAuth` SpEL bean lands in PR 5.
- `SystemConfigUpdated` domain event + Redis pub/sub cache invalidation — added when admin endpoints arrive (no in-app trigger exists yet in PR 3).
- Distributed cache. PR 3 ships a per-instance in-memory snapshot; spec allows 30-60s staleness across cluster nodes.

---

## Hard precondition (resolve BEFORE Task 1)

PR 3 ships **V9** while V6/V7/V8 are reserved for PR 7/8/9 (downstream). This requires Flyway `out-of-order=true`. **Verified via direct grep:** the current `app/src/main/resources/application.yml` (only application yml in the repo) has NO `out-of-order` setting in any profile, so Flyway defaults to `false` — V9 will REJECT to apply with V6/V7/V8 missing.

**Resolution (must happen as a separate prep commit BEFORE Task 1's V9 SQL is written):**

1. Enable `spring.flyway.out-of-order: true` in `app/src/main/resources/application.yml` under each profile that already configures Flyway.
2. Verify the change locally with `./gradlew :app:flywayInfo` (or boot once) — current state should still report V1..V5 applied with no failures.
3. Commit this single yml change with message `chore(flyway): enable out-of-order migrations for non-contiguous V9 (PR 3)`.

This precondition is documented as **Task 0** below. The implementer must complete Task 0 and verify before opening Task 1.

---

## Verified codebase facts (read once, applied throughout)

- `BaseEntity` lives at `common/src/main/java/com/pfplaybackend/api/common/entity/BaseEntity.java`. Audit columns auto-handled.
- `SecurityConfig` lives in `common/.../config/security/SecurityConfig.java` and currently:
  - Permits `/actuator/health`, `/api/v1/auth/oauth/**`, `/api/v1/users/(members|guests)/sign/**`, `/api/v1/partyrooms/link/**`, `/ws/**`, `/spec/**`, `/swagger-ui/**`, `/v3/api-docs/**`.
  - Gates `/api/v1/admin/**` with `hasRole("ADMIN")`.
  - Gates other `/api/**` with `authenticated()`.
- `spring-boot-starter-cache` is already a dependency (`app/build.gradle`), so `@EnableCaching` is available — but we will NOT use Spring Cache abstraction for the maintenance cache. Rationale: only 2 keys with identical 30s TTL; hand-rolled `AtomicReference<Snapshot>` is simpler and easier to test deterministically with a `Clock`.
- `app/.../administration/...` is the precedent BC package layout under `app`. `Operations` follows the same structure: `domain/`, `application/`, `adapter/in|out/`.
- Migrations V1-V5 already applied. V6-V8 are reserved for later PRs. **V9 is intentionally NOT V6** — schema doc reserves V6/V7/V8 for partyroom/admin_action/penalty work in PR 7-9. Flyway tolerates non-contiguous versions when set up with `outOfOrder=true`; verify in `application.yml` before assuming.
- `application*.yml` Flyway config — verify `outOfOrder` setting in Task 1 before relying on V9 jumping ahead of V6/V7/V8. If `outOfOrder: false` (default), the migration policy must be confirmed with a quick local fail-fast check; otherwise PR 3 must be re-numbered to V6 and the schema doc updated.
- `OncePerRequestFilter` is the Spring base class to extend. Registration via `FilterRegistrationBean<>` with explicit `setOrder(Ordered.HIGHEST_PRECEDENCE)` runs the filter before Spring Security (which uses `SecurityProperties.DEFAULT_FILTER_ORDER = -100`).
- AntPathMatcher is the idiomatic Spring path matcher for filter bypass; `org.springframework.util.AntPathMatcher`.

---

## File Structure

### Files Created

**Migration:**
- `app/src/main/resources/db/migration/V9__create_system_config.sql` — Flyway migration: `system_config` table + 2 seed rows.

**Operations BC scaffold (new package — first time):**
- `app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java` — value object wrapping `String` (validated: lowercase + dot/alnum, max 64 chars).
- `app/src/main/java/com/pfplaybackend/api/operations/domain/entity/data/SystemConfigData.java` — JPA entity. PK = `String configKey`. Columns: `configValue` TEXT, `description` VARCHAR(255), `updatedByAdministratorId` BIGINT NULL (loose ref), `updatedAt` DATETIME.
- `app/src/main/java/com/pfplaybackend/api/operations/adapter/out/persistence/SystemConfigRepository.java` — Spring Data JPA interface.

**Cache + filter:**
- `app/src/main/java/com/pfplaybackend/api/operations/application/service/SystemConfigCache.java` — in-memory snapshot with 30s TTL. Reads `maintenance.enabled` + `maintenance.message`.
- `app/src/main/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilter.java` — `OncePerRequestFilter`. Bypasses `/api/v1/admin/**` and `/actuator/health`; otherwise returns 503 when cache says enabled.
- `app/src/main/java/com/pfplaybackend/api/operations/config/MaintenanceModeFilterConfig.java` — `FilterRegistrationBean<MaintenanceModeFilter>` registration with `Ordered.HIGHEST_PRECEDENCE` order.

**Tests:**
- `app/src/test/java/com/pfplaybackend/api/operations/domain/value/ConfigKeyTest.java` — VO validation (POJO unit).
- `app/src/test/java/com/pfplaybackend/api/operations/application/service/SystemConfigCacheTest.java` — Mockito unit: TTL expiry, repo lookup memoization, default fallbacks for missing rows, injected `Clock` for deterministic time control.
- `app/src/test/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilterTest.java` — `@WebMvcTest`-style standalone or pure unit using `MockHttpServletRequest`/`MockHttpServletResponse`/`MockFilterChain`. Verifies bypass paths, 503 body shape, normal pass-through when disabled.
- `app/src/test/java/com/pfplaybackend/api/operations/SystemConfigRepositoryIntegrationTest.java` — `@SpringBootTest` + Testcontainers MySQL. Verifies V9 applied, two seed rows present, repository read/write works, PK uniqueness.

### Files Modified

None for PR 3. Filter registers via its own `@Configuration` bean; `common/SecurityConfig` is untouched (BC isolation).

### Files Removed

None.

---

## Test Strategy

| Layer | Test type | Notes |
|---|---|---|
| `ConfigKey` validation | POJO unit | Reject empty / >64 / uppercase / spaces; accept `maintenance.enabled` |
| `SystemConfigCache` | Mockito unit + injected `Clock` | TTL expiry triggers re-read; missing row → safe defaults (`enabled=false`, generic message); concurrent reads do not double-fetch (best-effort, not strict) |
| `MaintenanceModeFilter` | Pure unit (MockHttpServletRequest/Response/MockFilterChain) | Bypass `/api/v1/admin/**` and `/actuator/health` even when enabled; pass through normally when disabled; 503 + JSON body when enabled and not bypassed |
| `SystemConfigRepository` + V9 | `@SpringBootTest(webEnvironment=NONE)` + Testcontainers MySQL | V9 applies, seed rows readable via repo, PK unique on `config_key` |
| End-to-end smoke | Boot smoke test (Task 5) | Start app, hit `/api/v1/users/me` (401) → flip flag via SQL UPDATE → wait 30s → 503; flip back → 200/401 normal; `/api/v1/admin/**` returns 401 (not 503) regardless |

Decision rationale: a full `@SpringBootTest` for the filter would require launching the security chain end-to-end. A pure-unit filter test plus the manual smoke at Task 5 covers the matrix without flakiness. The integration test is scoped to repository + migration only.

---

## Chunk 0: Flyway Precondition

### Task 0: Enable Flyway out-of-order migrations

**Files:**
- Modify: `app/src/main/resources/application.yml`

- [ ] **Step 1: Inspect current Flyway config**

```bash
grep -nE "flyway|out[-_]?of[-_]?order" app/src/main/resources/application.yml
```

Expected: Flyway block exists with no `out-of-order` key. (If `out-of-order: true` is already present, skip Task 0 entirely and remove this task from the TodoWrite list.)

- [ ] **Step 2: Add `out-of-order: true` under the `spring.flyway` block**

Add `out-of-order: true` (or `outOfOrder: true` — match the case style of neighboring keys in the file) under the `spring.flyway` mapping. Apply to whichever profile-specific override sections also configure Flyway.

If unsure where to place it, the canonical Spring Boot key is `spring.flyway.out-of-order` (kebab-case relaxed binding).

- [ ] **Step 3: Verify Flyway info is unchanged**

Boot the app or run:

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=dev'
```

Wait for "Started ApiApplication". Then:

```bash
docker exec -i pfplay-mysql mysql -upfplay -ppfplay pfplay -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: V1..V5 rows, all `success=1`. No new versions applied (because no new SQL files exist yet).

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/application.yml
git commit -m "chore(flyway): enable out-of-order migrations for non-contiguous V9 (PR 3)

PR 3 ships V9 system_config while V6/V7/V8 are reserved for PR 7/8/9.
Flyway must be in out-of-order mode to apply V9 on top of V5.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md §9.1"
```

---

## Chunk 1: V9 Migration + SystemConfig Aggregate

### Task 1: Write V9 Flyway migration SQL

**Files:**
- Create: `app/src/main/resources/db/migration/V9__create_system_config.sql`

**Pre-task verification (do this first, do NOT skip):**

- [ ] **Step 0a: Confirm Task 0 (out-of-order) is committed**

```bash
grep -nE "out[-_]?of[-_]?order|outOfOrder" app/src/main/resources/application.yml
git log --oneline -1 -- app/src/main/resources/application.yml
```

Expected: grep finds `out-of-order: true` (or equivalent), and the latest commit touching application.yml is the Task 0 commit. If not, STOP — Task 0 was skipped or rolled back.

- [ ] **Step 0b: Confirm V9 not already present**

```bash
ls app/src/main/resources/db/migration/ | grep -E "^V[0-9]"
```

Expected: V1..V5 present; no V9. If V9 exists, STOP and surface to controller.

- [ ] **Step 1: Create V9 migration file**

Write `app/src/main/resources/db/migration/V9__create_system_config.sql`:

```sql
-- =====================================================
-- V9: Operations context — SystemConfig (key-value 범용 저장소)
--
-- 유지보수 모드 + 향후 feature flag 수용.
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
-- =====================================================

CREATE TABLE system_config (
    config_key                        VARCHAR(64)  NOT NULL,
    config_value                      TEXT         NOT NULL,
    description                       VARCHAR(255) NULL,
    updated_by_administrator_id       BIGINT       NULL,
    updated_at                        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 유지보수 모드 기본 설정 (off by default)
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('maintenance.enabled', 'false', '유지보수 모드 활성 여부 (true일 때 일반 API 503)'),
    ('maintenance.message', '시스템 점검 중입니다. 잠시 후 다시 시도해주세요.', '유지보수 안내 메시지');
```

Notes for the implementer:
- `updated_by_administrator_id` is BIGINT NULL — loose ref to Administration BC, no FK across BCs (per spec §4.9).
- DDL matches schema doc §4.6.1 verbatim. `updated_at` is `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` (no `NOT NULL`); MySQL implicitly makes the column NOT NULL when given an explicit non-null DEFAULT, so behavior is equivalent without polluting the DDL diff.
- Hibernate must consider `updated_at` non-nullable at the entity level (`nullable = false`) since every JPA-driven INSERT will populate it from Java; the DB DEFAULT only matters for raw SQL INSERTs (which is exactly the V9 seed rows).

- [ ] **Step 2: Run boot to apply V9**

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=dev'
```

Or, if a clean Flyway test is preferred:

```bash
./gradlew :app:flywayMigrate
```

Expected: V9 applies cleanly. Schema history table shows row for V9 with `success=1`.

- [ ] **Step 3: Verify rows present**

```bash
docker exec -i pfplay-mysql mysql -upfplay -ppfplay pfplay -e "SELECT config_key, config_value FROM system_config;"
```

Expected:
```
config_key            | config_value
maintenance.enabled   | false
maintenance.message   | 시스템 점검 중입니다. 잠시 후 다시 시도해주세요.
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V9__create_system_config.sql
git commit -m "feat(operations): add V9 Flyway migration for system_config table

Creates key-value system_config table with two seed rows:
- maintenance.enabled (false by default)
- maintenance.message (default Korean message)

Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6"
```

---

### Task 2: Create Operations BC scaffold + SystemConfig aggregate

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java`
- Create: `app/src/main/java/com/pfplaybackend/api/operations/domain/entity/data/SystemConfigData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/operations/adapter/out/persistence/SystemConfigRepository.java`
- Test: `app/src/test/java/com/pfplaybackend/api/operations/domain/value/ConfigKeyTest.java`

- [ ] **Step 1: Write the failing ConfigKey VO test**

Create `app/src/test/java/com/pfplaybackend/api/operations/domain/value/ConfigKeyTest.java`:

```java
package com.pfplaybackend.api.operations.domain.value;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigKeyTest {

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> ConfigKey.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> ConfigKey.of(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigKey.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_overlong_value() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> ConfigKey.of(tooLong))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_uppercase() {
        assertThatThrownBy(() -> ConfigKey.of("Maintenance.Enabled"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_spaces() {
        assertThatThrownBy(() -> ConfigKey.of("maintenance enabled"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_dotted_lowercase_alnum() {
        assertThat(ConfigKey.of("maintenance.enabled").value())
            .isEqualTo("maintenance.enabled");
        assertThat(ConfigKey.of("feature.avatar_v2.enabled").value())
            .isEqualTo("feature.avatar_v2.enabled");
    }

    @Test
    void provides_well_known_constants() {
        assertThat(ConfigKey.MAINTENANCE_ENABLED.value()).isEqualTo("maintenance.enabled");
        assertThat(ConfigKey.MAINTENANCE_MESSAGE.value()).isEqualTo("maintenance.message");
    }
}
```

- [ ] **Step 2: Run the test — confirm FAIL**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.domain.value.ConfigKeyTest
```

Expected: compilation failure (`ConfigKey` does not exist).

- [ ] **Step 3: Implement ConfigKey VO**

Create `app/src/main/java/com/pfplaybackend/api/operations/domain/value/ConfigKey.java`:

```java
package com.pfplaybackend.api.operations.domain.value;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object for SystemConfig PK.
 *
 * Validation: lowercase ASCII letters/digits, dots, and underscores. Max 64 chars.
 * Examples: maintenance.enabled, feature.avatar_v2.enabled
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.3.4
 */
public record ConfigKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9_]+(\\.[a-z0-9_]+)*$");
    private static final int MAX_LENGTH = 64;

    public ConfigKey {
        Objects.requireNonNull(value, "config_key must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("config_key must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("config_key must be <= " + MAX_LENGTH + " chars");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "config_key must be lowercase alnum/underscore segments separated by dots: " + value);
        }
    }

    public static ConfigKey of(String value) {
        return new ConfigKey(value);
    }

    // Well-known keys
    public static final ConfigKey MAINTENANCE_ENABLED = new ConfigKey("maintenance.enabled");
    public static final ConfigKey MAINTENANCE_MESSAGE = new ConfigKey("maintenance.message");
}
```

- [ ] **Step 4: Run the test — confirm PASS**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.domain.value.ConfigKeyTest
```

Expected: 7/7 pass.

- [ ] **Step 5: Implement SystemConfigData JPA entity**

Create `app/src/main/java/com/pfplaybackend/api/operations/domain/entity/data/SystemConfigData.java`:

```java
package com.pfplaybackend.api.operations.domain.entity.data;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * SystemConfig aggregate root persistence entity (Operations BC).
 *
 * Key-value store. PK = config_key (String).
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
 *       docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.3.4
 */
@Entity
@Table(name = "system_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicUpdate
public class SystemConfigData {

    @Id
    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_by_administrator_id")
    private Long updatedByAdministratorId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private SystemConfigData(String configKey, String configValue, String description,
                             Long updatedByAdministratorId, LocalDateTime updatedAt) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
        this.updatedByAdministratorId = updatedByAdministratorId;
        this.updatedAt = updatedAt;
    }

    /** PR 3 has no in-app writers; updates go through SQL. Factory reserved for PR 6 admin endpoints. */
    public static SystemConfigData create(String configKey, String configValue,
                                          String description, Long updatedByAdministratorId) {
        return SystemConfigData.builder()
            .configKey(configKey)
            .configValue(configValue)
            .description(description)
            .updatedByAdministratorId(updatedByAdministratorId)
            .updatedAt(LocalDateTime.now())
            .build();
    }

    /** Reserved for PR 6. */
    public void updateValue(String newValue, Long updatedByAdministratorId) {
        this.configValue = newValue;
        this.updatedByAdministratorId = updatedByAdministratorId;
        this.updatedAt = LocalDateTime.now();
    }
}
```

Note for the implementer:
- This entity does NOT extend `BaseEntity`. Reason: BaseEntity provides `createdAt/createdBy/updatedAt/updatedBy` audit columns; the V9 schema only defines `updated_at` (no `created_at`/`created_by`/`updated_by` text columns). The `updated_by_administrator_id` is a loose-ref BIGINT, not a username string. Inheriting BaseEntity would attempt to bind nonexistent columns and break Hibernate validation.
- `@DynamicInsert` is omitted because every column has either a NOT NULL value or is explicitly inserted.
- No `@Enumerated`, no `columnDefinition = "VARCHAR(N)"` workaround needed (no enum columns).

- [ ] **Step 6: Implement SystemConfigRepository**

Create `app/src/main/java/com/pfplaybackend/api/operations/adapter/out/persistence/SystemConfigRepository.java`:

```java
package com.pfplaybackend.api.operations.adapter.out.persistence;

import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemConfigRepository extends JpaRepository<SystemConfigData, String> {
    Optional<SystemConfigData> findByConfigKey(String configKey);
}
```

- [ ] **Step 7: Compile + run all tests**

```bash
./gradlew :app:build -x test :app:test --tests com.pfplaybackend.api.operations.*
```

Expected: build success, ConfigKey tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/operations app/src/test/java/com/pfplaybackend/api/operations
git commit -m "feat(operations): add SystemConfig aggregate (entity + value object + repository)

- ConfigKey value object with format validation (lowercase, dotted segments)
- SystemConfigData JPA entity (PK=config_key, no BaseEntity audit cols)
- SystemConfigRepository (Spring Data)
- Well-known constants ConfigKey.MAINTENANCE_ENABLED / MAINTENANCE_MESSAGE

Spec: docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.3.4"
```

---

### Task 3: Repository integration test (Testcontainers MySQL)

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/operations/SystemConfigRepositoryIntegrationTest.java`

**Goal:** Pin V9 + repository wiring with a real MySQL instance, mirroring the Testcontainers pattern used in PR 2 for `AdministratorRepositoryIntegrationTest`.

- [ ] **Step 1: Locate the existing Testcontainers config**

```bash
find app/src/test -name "TestContainerConfig*.java" -o -name "*Testcontainer*Config*.java" 2>/dev/null
```

Identify the existing MySQL Testcontainers `@TestConfiguration` + locate an example consumer (PR 2's `AdministratorRepositoryIntegrationTest`). Reuse the same imports and `@Import` declaration.

- [ ] **Step 2: Write the integration test**

Create `app/src/test/java/com/pfplaybackend/api/operations/SystemConfigRepositoryIntegrationTest.java`:

```java
package com.pfplaybackend.api.operations;

import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
// import the TestContainerConfig identified in Step 1

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(/* TestContainerConfig.class — match PR 2 pattern */)
class SystemConfigRepositoryIntegrationTest {

    @Autowired
    SystemConfigRepository repository;

    @Test
    void v9_seeds_two_rows() {
        assertThat(repository.count()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void maintenance_enabled_seed_present_and_false() {
        Optional<SystemConfigData> row = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value());
        assertThat(row).isPresent();
        assertThat(row.get().getConfigValue()).isEqualTo("false");
    }

    @Test
    void maintenance_message_seed_present_with_default() {
        Optional<SystemConfigData> row = repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value());
        assertThat(row).isPresent();
        assertThat(row.get().getConfigValue()).isNotBlank();
    }

    @Test
    void update_round_trips_via_repository() {
        SystemConfigData row = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()).orElseThrow();
        row.updateValue("true", 1L);
        repository.saveAndFlush(row);

        SystemConfigData reloaded = repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()).orElseThrow();
        assertThat(reloaded.getConfigValue()).isEqualTo("true");
        assertThat(reloaded.getUpdatedByAdministratorId()).isEqualTo(1L);

        // Restore so test ordering doesn't leak
        reloaded.updateValue("false", null);
        repository.saveAndFlush(reloaded);
    }
}
```

The implementer must replace the `TestContainerConfig` reference with the actual class found in Step 1. If a separate-DB pattern is the existing convention (e.g., `@DirtiesContext` per test method), follow that instead — match what PR 2 does.

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.SystemConfigRepositoryIntegrationTest
```

Expected: 4/4 pass against fresh MySQL container.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/pfplaybackend/api/operations/SystemConfigRepositoryIntegrationTest.java
git commit -m "test(operations): add Testcontainers integration test for SystemConfig + V9

Verifies V9 migration applied, both seed rows readable via repo,
update round-trip works through JPA."
```

---

## Chunk 2: Cache + Filter

### Task 4: Implement SystemConfigCache (in-memory TTL snapshot)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/operations/application/service/SystemConfigCache.java`
- Test: `app/src/test/java/com/pfplaybackend/api/operations/application/service/SystemConfigCacheTest.java`

**Design:**
- Single source-of-truth: `SystemConfigRepository`.
- Snapshot record `Snapshot(boolean maintenanceEnabled, String maintenanceMessage, Instant fetchedAt)` held in `AtomicReference`.
- TTL: 30 seconds (constant `SNAPSHOT_TTL = Duration.ofSeconds(30)`).
- On `isMaintenanceMode()` / `getMaintenanceMessage()` call: if snapshot is null OR `now - fetchedAt > TTL` → re-fetch from repo and CAS the new snapshot. Concurrent re-fetches are acceptable (idempotent reads); we accept best-effort de-duplication via CAS.
- Defaults if row missing: `maintenanceEnabled = false`, `maintenanceMessage = "시스템 점검 중입니다."` (literal fallback, separate from V9 seed text — never a NullPointerException).
- Inject `Clock` for deterministic tests. Production wiring uses `Clock.systemUTC()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pfplaybackend/api/operations/application/service/SystemConfigCacheTest.java`:

```java
package com.pfplaybackend.api.operations.application.service;

import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemConfigCacheTest {

    @Mock
    SystemConfigRepository repository;

    SystemConfigCache cache;
    MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-04-26T00:00:00Z"));
        cache = new SystemConfigCache(repository, clock);
    }

    @Test
    void isMaintenanceMode_reads_repo_on_first_call() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "true")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "down")));

        assertThat(cache.isMaintenanceMode()).isTrue();
        assertThat(cache.getMaintenanceMessage()).isEqualTo("down");
    }

    @Test
    void second_call_within_ttl_does_not_hit_repo() {
        when(repository.findByConfigKey(anyString()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "false")))
            .thenReturn(Optional.of(seed("maintenance.message", "")));

        cache.isMaintenanceMode();
        clock.advanceSeconds(29);
        cache.isMaintenanceMode();
        cache.getMaintenanceMessage();

        verify(repository, times(2)).findByConfigKey(anyString()); // initial pair only
    }

    @Test
    void call_after_ttl_re_fetches() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "false")))
            .thenReturn(Optional.of(seed("maintenance.enabled", "true")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "ok")));

        assertThat(cache.isMaintenanceMode()).isFalse();
        clock.advanceSeconds(31);
        assertThat(cache.isMaintenanceMode()).isTrue();

        verify(repository, times(2)).findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value());
    }

    @Test
    void missing_rows_yield_safe_defaults() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        assertThat(cache.isMaintenanceMode()).isFalse();
        assertThat(cache.getMaintenanceMessage()).isNotBlank();
    }

    @Test
    void invalid_value_for_enabled_treated_as_false() {
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_ENABLED.value()))
            .thenReturn(Optional.of(seed("maintenance.enabled", "yes-please")));
        when(repository.findByConfigKey(ConfigKey.MAINTENANCE_MESSAGE.value()))
            .thenReturn(Optional.of(seed("maintenance.message", "x")));

        assertThat(cache.isMaintenanceMode()).isFalse();
    }

    private SystemConfigData seed(String key, String value) {
        return SystemConfigData.create(key, value, null, null);
    }

    /** Test-only mutable clock. */
    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }
}
```

- [ ] **Step 2: Run the test — confirm FAIL**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.application.service.SystemConfigCacheTest
```

Expected: compilation failure (`SystemConfigCache` missing).

- [ ] **Step 3: Implement SystemConfigCache**

Create `app/src/main/java/com/pfplaybackend/api/operations/application/service/SystemConfigCache.java`:

```java
package com.pfplaybackend.api.operations.application.service;

import com.pfplaybackend.api.operations.adapter.out.persistence.SystemConfigRepository;
import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import com.pfplaybackend.api.operations.domain.value.ConfigKey;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory snapshot cache for SystemConfig (maintenance mode keys).
 *
 * 30-second TTL. Per-instance — no distributed invalidation in PR 3.
 * Tolerated staleness window matches spec (§9.3 "system_config 캐시 stale: 캐시 TTL 30~60초").
 *
 * PR 6 will add a SystemConfigUpdated domain event + listener that calls invalidate()
 * when admin endpoints toggle maintenance.
 */
@Component
public class SystemConfigCache {

    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);
    static final String DEFAULT_MAINTENANCE_MESSAGE = "시스템 점검 중입니다.";

    private final SystemConfigRepository repository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public SystemConfigCache(SystemConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public boolean isMaintenanceMode() {
        return current().maintenanceEnabled;
    }

    public String getMaintenanceMessage() {
        return current().maintenanceMessage;
    }

    /** Public so PR 6's event listener can force-invalidate after admin toggle. */
    public void invalidate() {
        snapshotRef.set(null);
    }

    private Snapshot current() {
        Snapshot existing = snapshotRef.get();
        Instant now = clock.instant();
        if (existing != null && Duration.between(existing.fetchedAt, now).compareTo(SNAPSHOT_TTL) < 0) {
            return existing;
        }
        Snapshot fresh = fetch(now);
        snapshotRef.compareAndSet(existing, fresh);
        return fresh;
    }

    private Snapshot fetch(Instant now) {
        boolean enabled = readBool(ConfigKey.MAINTENANCE_ENABLED, false);
        String message = readString(ConfigKey.MAINTENANCE_MESSAGE, DEFAULT_MAINTENANCE_MESSAGE);
        return new Snapshot(enabled, message, now);
    }

    /**
     * Fail-open by design: missing rows or malformed values fall back to {@code fallback}
     * (which is {@code false} for maintenance.enabled). A corrupted seed must NOT brick
     * the platform; operators must explicitly write the literal string "true" to engage
     * maintenance mode. Inverting this would risk locking everyone out from a typo.
     */
    private boolean readBool(ConfigKey key, boolean fallback) {
        Optional<SystemConfigData> row = repository.findByConfigKey(key.value());
        if (row.isEmpty()) return fallback;
        String v = row.get().getConfigValue();
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        return fallback;
    }

    private String readString(ConfigKey key, String fallback) {
        return repository.findByConfigKey(key.value())
            .map(SystemConfigData::getConfigValue)
            .filter(s -> !s.isBlank())
            .orElse(fallback);
    }

    private record Snapshot(boolean maintenanceEnabled, String maintenanceMessage, Instant fetchedAt) {}
}
```

- [ ] **Step 4: Wire Clock bean (if not already present)**

Check first:
```bash
grep -rn "Clock.systemUTC\|@Bean.*Clock" app/src/main/java/com/pfplaybackend/api/bootstrap/ common/src/main/java/com/pfplaybackend/api/common/config/ 2>/dev/null
```

If a `Clock` bean already exists project-wide, skip this step. If not, add it to a small `OperationsConfig` (so PR 3 doesn't touch global config):

Create `app/src/main/java/com/pfplaybackend/api/operations/config/OperationsConfig.java`:

```java
package com.pfplaybackend.api.operations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OperationsConfig {

    @Bean
    public Clock systemConfigClock() {
        return Clock.systemUTC();
    }
}
```

If a `Clock` bean already exists, document the decision in your commit and skip creating `OperationsConfig` for now (Task 5's filter config will still create the file).

- [ ] **Step 5: Run the test — confirm PASS**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.application.service.SystemConfigCacheTest
```

Expected: 5/5 pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/operations/application/service/SystemConfigCache.java \
        app/src/main/java/com/pfplaybackend/api/operations/config/OperationsConfig.java \
        app/src/test/java/com/pfplaybackend/api/operations/application/service/SystemConfigCacheTest.java
git commit -m "feat(operations): add SystemConfigCache with 30s TTL snapshot

In-memory AtomicReference snapshot. Reads maintenance.enabled and
maintenance.message; safe defaults when rows missing or value malformed.
Clock injected for deterministic tests.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-roadmap.md §9.3"
```

---

### Task 5: Implement MaintenanceModeFilter + registration

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilter.java`
- Create: `app/src/main/java/com/pfplaybackend/api/operations/config/MaintenanceModeFilterConfig.java`
- Test: `app/src/test/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilterTest.java`

**Design:**
- Extends `OncePerRequestFilter`.
- Bypass paths (do NOT apply 503 even when enabled): `/api/v1/admin/**`, `/actuator/health`. Match via `AntPathMatcher`.
- When enabled and not bypassed: respond 503 with JSON body `{"message": "<maintenance.message>"}`, content-type `application/json;charset=UTF-8`. Do NOT call the filter chain.
- When disabled: pass through to the next filter.
- Registered via `FilterRegistrationBean` at `Ordered.HIGHEST_PRECEDENCE`. URL patterns: `/*`. The bypass logic lives in the filter itself, not the registration, so all requests are visible for accurate 503 vs pass-through decisions.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilterTest.java`:

```java
package com.pfplaybackend.api.operations.adapter.in.web;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaintenanceModeFilterTest {

    @Mock
    SystemConfigCache cache;

    MaintenanceModeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MaintenanceModeFilter(cache);
    }

    @Test
    void passes_through_when_disabled() throws ServletException, IOException {
        when(cache.isMaintenanceMode()).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Chain WAS invoked (filter passed through). Filter did NOT touch the response.
        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void returns_503_with_message_when_enabled_and_path_not_bypassed() throws ServletException, IOException {
        when(cache.isMaintenanceMode()).thenReturn(true);
        when(cache.getMaintenanceMessage()).thenReturn("점검중");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("점검중");
        assertThat(chain.getRequest()).isNull(); // chain not invoked
    }

    @Test
    void bypasses_admin_paths_even_when_enabled() throws ServletException, IOException {
        when(cache.isMaintenanceMode()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/system/config/maintenance");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        // Chain invoked, filter did NOT short-circuit despite maintenance enabled.
        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void bypasses_actuator_health_even_when_enabled() throws ServletException, IOException {
        when(cache.isMaintenanceMode()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(res.getContentAsString()).isEmpty();
    }

    @Test
    void does_not_bypass_admin_lookalike_path() throws ServletException, IOException {
        when(cache.isMaintenanceMode()).thenReturn(true);
        when(cache.getMaintenanceMessage()).thenReturn("점검중");

        // Path contains "admin" but is not under /api/v1/admin
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/admin-friend-list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
    }
}
```

- [ ] **Step 2: Run the test — confirm FAIL**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.adapter.in.web.MaintenanceModeFilterTest
```

Expected: compilation failure.

- [ ] **Step 3: Implement MaintenanceModeFilter**

Create `app/src/main/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilter.java`:

```java
package com.pfplaybackend.api.operations.adapter.in.web;

import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Returns HTTP 503 for non-admin traffic when maintenance mode is enabled.
 *
 * Bypass paths: /api/v1/admin/** , /actuator/health.
 * Bypass means "always pass through to next filter" — ignores maintenance flag.
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-features.md §6.E-1
 *       docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6.2
 */
public class MaintenanceModeFilter extends OncePerRequestFilter {

    private static final List<String> BYPASS_PATTERNS = List.of(
        "/api/v1/admin/**",
        "/actuator/health"
    );
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final SystemConfigCache cache;

    public MaintenanceModeFilter(SystemConfigCache cache) {
        this.cache = cache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBypassed(request) || !cache.isMaintenanceMode()) {
            chain.doFilter(request, response);
            return;
        }
        respond503(response);
    }

    private boolean isBypassed(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : BYPASS_PATTERNS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void respond503(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        String body = "{\"message\":" + jsonString(cache.getMaintenanceMessage()) + "}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private String jsonString(String s) {
        // Minimal JSON escape — only quotes and backslashes. Maintenance message is operator-controlled.
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
```

Notes for implementer:
- The minimal JSON escaper avoids a Jackson dependency in the filter. The body has a single field with operator-controlled text — no user input. If reviewer pushes back on hand-rolled JSON, switch to `ObjectMapper` injection (Spring autowires the boot-managed instance).
- `OncePerRequestFilter` ensures the filter runs once per request even with forwarding/error dispatch.

- [ ] **Step 4: Implement filter registration**

Create `app/src/main/java/com/pfplaybackend/api/operations/config/MaintenanceModeFilterConfig.java`:

```java
package com.pfplaybackend.api.operations.config;

import com.pfplaybackend.api.operations.adapter.in.web.MaintenanceModeFilter;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class MaintenanceModeFilterConfig {

    /**
     * Register MaintenanceModeFilter at HIGHEST_PRECEDENCE so it runs before
     * the Spring Security filter chain (SecurityProperties.DEFAULT_FILTER_ORDER = -100).
     *
     * Bypass logic for /api/v1/admin/** and /actuator/health is inside the filter,
     * not the registration — registration applies to all paths.
     */
    @Bean
    public FilterRegistrationBean<MaintenanceModeFilter> maintenanceModeFilterRegistration(
            SystemConfigCache cache) {
        FilterRegistrationBean<MaintenanceModeFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new MaintenanceModeFilter(cache));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setName("maintenanceModeFilter");
        return bean;
    }
}
```

Important — the filter is **NOT** a `@Component`. Reason: if it were, Spring Boot's auto-registration would wire it into the chain at default order, and the `FilterRegistrationBean` would register it a second time (also at HIGHEST_PRECEDENCE) — duplicate execution. Keeping the filter class plain + manually constructing it inside the registration ensures single registration at the explicit order.

- [ ] **Step 5: Run the filter tests — confirm PASS**

```bash
./gradlew :app:test --tests com.pfplaybackend.api.operations.adapter.in.web.MaintenanceModeFilterTest
```

Expected: 5/5 pass.

- [ ] **Step 6: Compile + run all operations tests**

```bash
./gradlew :app:test --tests "com.pfplaybackend.api.operations.*"
```

Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilter.java \
        app/src/main/java/com/pfplaybackend/api/operations/config/MaintenanceModeFilterConfig.java \
        app/src/test/java/com/pfplaybackend/api/operations/adapter/in/web/MaintenanceModeFilterTest.java
git commit -m "feat(operations): add MaintenanceModeFilter (503 except admin/health)

OncePerRequestFilter registered at HIGHEST_PRECEDENCE via FilterRegistrationBean.
Bypass: /api/v1/admin/** and /actuator/health (always pass through).
Otherwise returns 503 with JSON body containing maintenance.message
when SystemConfigCache.isMaintenanceMode() is true.

Spec: docs/superpowers/specs/2026-04-19-admin-platform-features.md §6.E-1"
```

---

## Chunk 3: End-to-End Verification

### Task 6: Boot smoke test (V9 + filter live behavior)

**Files:** none created. Manual + scripted verification.

- [ ] **Step 1: Stop any running app instance**

```bash
taskkill //F //IM java.exe 2>/dev/null; echo "stopped"
```

- [ ] **Step 2: Boot the app**

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=dev'
```

Wait until logs show `Started ApiApplication in ... seconds`.

In another terminal verify Flyway log line shows V9 applied (or already-current if PR 3 has booted before).

- [ ] **Step 3: Verify filter passes through when maintenance disabled**

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/users/me
```

Expected: `401` (unauthenticated). NOT `503`. This proves the filter is wired and lets normal traffic flow.

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
```

Expected: `200` (or 503 only if a downstream component is unhealthy — verify that's unrelated to the maintenance filter by checking response body).

- [ ] **Step 4: Flip maintenance.enabled = true via direct SQL**

```bash
docker exec -i pfplay-mysql mysql -upfplay -ppfplay pfplay -e "UPDATE system_config SET config_value='true' WHERE config_key='maintenance.enabled';"
docker exec -i pfplay-mysql mysql -upfplay -ppfplay pfplay -e "SELECT * FROM system_config WHERE config_key='maintenance.enabled';"
```

Expected: 1 row affected; SELECT shows `config_value=true`.

- [ ] **Step 5: Wait for cache TTL to expire (30s)**

```bash
sleep 31
```

- [ ] **Step 6: Verify 503 for non-admin path**

```bash
curl -sS -i http://localhost:8080/api/v1/users/me
```

Expected:
- Status line: `HTTP/1.1 503 Service Unavailable`
- Content-Type contains `application/json`
- Body: `{"message":"시스템 점검 중입니다. 잠시 후 다시 시도해주세요."}` (or a similar Korean string from V9 seed)

- [ ] **Step 7: Verify admin path is bypassed**

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/admin/anything
```

Expected: `401` or `403` (NOT `503`). Proves the filter does NOT intercept admin routes when enabled.

- [ ] **Step 8: Verify health is bypassed**

```bash
curl -sS http://localhost:8080/actuator/health | grep -q '"status":"UP"' && echo "health-up" || echo "FAIL: actuator/health not UP"
```

Expected output: `health-up`. If `FAIL`, abort the smoke test — investigate actuator independently before continuing. The maintenance filter is in the bypass list for this path, so any non-UP result is a separate problem (DB/Redis/etc.) that should not be masked by the maintenance check.

- [ ] **Step 9: Restore maintenance.enabled = false**

```bash
docker exec -i pfplay-mysql mysql -upfplay -ppfplay pfplay -e "UPDATE system_config SET config_value='false' WHERE config_key='maintenance.enabled';"
sleep 31
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/users/me
```

Expected: back to `401`.

- [ ] **Step 10: Stop the app**

```bash
taskkill //F //IM java.exe
```

- [ ] **Step 11: Run the full test suite**

```bash
./gradlew :app:test
```

Expected: all green.

- [ ] **Step 12: Update REFACTORING_ROADMAP.md (if applicable)**

This is optional — if the repo's living roadmap or CHANGELOG mentions PR 3, update it. Otherwise skip.

- [ ] **Step 13: Final commit (if Step 12 produced changes; otherwise skip)**

```bash
git add docs/REFACTORING_ROADMAP.md
git commit -m "docs: mark PR 3 (V9 + maintenance filter) complete"
```

If no doc changes, no commit.

---

## Final Checkpoint

Before declaring PR 3 done, confirm:

- [ ] Task 0 prep commit: `spring.flyway.out-of-order: true` set in application.yml.
- [ ] V9 applied and seed rows visible in `system_config`.
- [ ] All operations BC tests pass (`./gradlew :app:test --tests "com.pfplaybackend.api.operations.*"`).
- [ ] Boot smoke test (Task 6) verified the 503/200 matrix.
- [ ] No changes to `common/SecurityConfig.java`.
- [ ] No new dependencies added to `app/build.gradle`.
- [ ] No `@Component` on `MaintenanceModeFilter` (avoids double registration).
- [ ] No admin endpoint added (deferred to PR 6).
- [ ] No event/listener added for `SystemConfigUpdated` (deferred to PR 6).

---

## Spec Coverage Summary

| Requirement (spec §) | Implementation | Verified by |
|---|---|---|
| §3.1 Operations BC table row | `app/.../operations/...` package created | Task 2 commits |
| §3.3.4 SystemConfig aggregate (configKey, configValue, description, updatedByAdministratorId, updatedAt) | `SystemConfigData` entity + `ConfigKey` VO | Task 2 + Task 3 integration test |
| §4.6.1 V9 DDL (table shape + 2 seeds) | `V9__create_system_config.sql` | Task 1 + Task 3 integration test |
| §4.6.2 SystemConfigCache 30~60s TTL | `SystemConfigCache` with 30s `SNAPSHOT_TTL` | Task 4 unit test |
| §4.6.2 MaintenanceModeFilter 503 except `/api/v1/admin/**` | `MaintenanceModeFilter` + `FilterRegistrationBean` at HIGHEST_PRECEDENCE | Task 5 unit + Task 6 smoke |
| §6.E-1 bypass `/api/v1/admin/**`, `/actuator/health` | `BYPASS_PATTERNS` in filter | Task 5 unit + Task 6 smoke |
| §9.1 PR 3 sizing (S, 3-5 tasks) | 6 tasks (verification adds one) — within S envelope | — |
| §9.3 캐시 stale 위험 완화 | TTL 30s + invalidate() public for PR 6 | Task 4 |

## Out of Scope (deferred — confirmed)

| Item | Where it lands |
|---|---|
| `GET/PATCH /api/v1/admin/system/config/maintenance` endpoints | PR 6 (admin CRUD) with `adminAuth` SpEL bean |
| `SystemConfigUpdated` domain event + listener cache invalidation | PR 6 alongside endpoints |
| Distributed cache (Redis) | Future, when multi-instance + sub-30s staleness needed |
| Feature flag UX | §11.1.5 — much later |

---

**Estimated effort:** S (3-5 tasks per spec; this plan has 7 — 1 prep (Task 0) + 5 implementation + 1 smoke verification. Task 0 is a one-line yml edit, so the implementation envelope still fits S sizing).

**Branch:** `feature/admin-auth-iam-schema` (continued from PR 2 HEAD `b8e8138f`).
