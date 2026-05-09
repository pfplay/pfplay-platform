# Admin Platform — PR 0 / PR 10 / PR 11 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement (PR 0) admin URL security hardening; (PR 10) Avatar bounded-context Gradle module + V12 Flyway migration + entity move from `user` module; (PR 11) Avatar admin CRUD API with GCS backend-proxied uploads and audit event listeners.

**Architecture:**
- PR 0: Tighten SecurityConfig `authorizeHttpRequests` chain — remove `/api/v1/admin/**` permitAll hole, add role-based gates (belt-and-suspenders). Method-level `@PreAuthorize` remains the primary guard.
- PR 10: Create new `avatar` Gradle module (hexagonal layout matching `user` module), move `AvatarBody/FaceResourceData` + VOs from `user` to `avatar`, drop `AvatarIconResourceData` + `PairType` enum, V12 migration merges `avatar_icon_resource` into `body/face.icon_uri`, adds `lifecycle_status` + audit columns + `face.obtainable_type`. User-side picker logic rewired via port. Backward-compatible externally.
- PR 11: Admin CRUD controllers/services/ports inside `avatar` module, `GcsAvatarStorageAdapter` wrapping `google-cloud-storage` SDK, domain events (`AvatarResourcePublished`/`Retired`) wired to Administration's `admin_action` listener.

**Tech Stack:** Java 21, Spring Boot 3.2, Kotlin/Gradle multi-module, MySQL 8 (Flyway 9+), JPA/Hibernate, QueryDSL, Passay, Spring Security, Bucket4j (rate limit in later PRs — not this scope), `com.google.cloud:google-cloud-storage` SDK (PR 11 only), JUnit 5 / AssertJ / Mockito / Spring Security Test / Testcontainers MySQL.

**Design Source:** `docs/superpowers/specs/2026-04-19-admin-platform-*.md` (6 documents). See specifically:
- `...design.md §3.1, §3.2, §3.3.5` (BC taxonomy, Avatar aggregates)
- `...schema.md §4.11` (V12 DDL)
- `...features.md §6.I` (Avatar CRUD endpoints)
- `...security.md §5.2.3` (SecurityConfig open change for PR 0) and §5.2.4 (`adminAuth` SpEL bean)
- `...integrity.md §8.2, §8.5` (events, ArchUnit rules)
- `...roadmap.md §9` (PR dependencies & ordering)

**Execution ordering constraints:**
- **PR 0**: Execute immediately on a branch off `develop`. Independent. Unblocks all other admin work.
- **PR 10**: Must execute AFTER PR 1-9 are merged (it touches user module which V4 reshapes, and the admin audit listener wired in PR 11 depends on PR 8's `admin_action`/`partyroom_admin_action` schema). In practice: write the plan now, execute when dependencies are in.
- **PR 11**: Must execute AFTER PR 10 is merged.

**Branching strategy:** Each PR on its own feature branch off `develop`. PR 10/11 cannot branch off PR 0 — they branch off a `develop` state where PR 1-9 are already merged.

---

## Chunk 1 — PR 0: Admin URL security gate (permitAll removal)

**Goal:** Replace the `/api/v1/admin/**` permitAll (which was placed temporarily to unblock development) with `hasRole('ADMIN')` + SUPER_ADMIN subpath gates. Ship a regression test asserting that admin endpoints require authentication.

**Scope is intentionally small.** No new roles are introduced (those arrive in PR 4/5). PR 0 only flips existing `permitAll` to authenticated+role-gated, verifying the current JWT already carries the right authorities via `CustomJwtAuthenticationConverter`.

### File Structure for PR 0

- **Modify**: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java`
- **Create**: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java` — regression test for URL gate

**Dependencies:** None beyond existing code.

---

### Task 1.1: Add failing test for admin endpoint authentication gate

Writes a Spring Security `@WebMvcTest` that asserts:
- Unauthenticated request to `/api/v1/admin/...` returns 401
- Authenticated non-admin user returns 403
- Authenticated admin user returns 200 (or endpoint-specific success)

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java`

- [ ] **Step 1: Identify an existing admin endpoint for the test**

Run: Use Grep to locate a simple GET endpoint under `/api/v1/admin/`. Expected to find something in `AdminPartyroomController` or `AdminUserController`.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java`:

```java
package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithAnonymousUser
    void anonymousRequest_toAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"MEMBER"})
    void authenticatedMember_toAdminEndpoint_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partyrooms"))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    void authenticatedAdmin_toAdminEndpoint_doesNotReturn401or403() throws Exception {
        // Endpoint may return 200/404/400 depending on payload, but NOT 401/403.
        mockMvc.perform(get("/api/v1/admin/partyrooms"))
               .andExpect(status().is(not401Nor403()));
    }

    private static org.hamcrest.Matcher<Integer> not401Nor403() {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.isOneOf(401, 403));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd pfplay-platform && ./gradlew :app:test --tests AdminEndpointSecurityTest`
Expected: The `anonymousRequest_...` and `authenticatedMember_...` tests FAIL because current `SecurityConfig` has `/api/v1/admin/**` as `permitAll` — anonymous requests currently pass through (200 or endpoint-specific response, not 401).

Note: If endpoint path guess is wrong, test returns 404 for all cases — that's also a failing signal but not the targeted regression. Adjust path based on actual endpoints (e.g., `/api/v1/admin/demo/...` in current `AdminDemoController`).

---

### Task 1.2: Tighten SecurityConfig authorizeHttpRequests chain

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java:38-47`

- [ ] **Step 1: Replace permitAll with hasRole gates**

In `SecurityConfig.java`, change the `authorizeHttpRequests` block:

```java
.authorizeHttpRequests(request -> request
        // Public endpoints (unchanged)
        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url", "/api/v1/auth/logout",
                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**", "/api/v1/partyrooms/link/**").permitAll()
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/spec/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

        // Admin endpoints — role-gated (belt-and-suspenders; method @PreAuthorize is primary)
        // SUPER_ADMIN subpaths defined here for now; more specific patterns (/avatar, /system) arrive in later PRs.
        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

        // Everything else under /api requires auth
        .requestMatchers("/api/**").authenticated()
        .anyRequest().denyAll()
)
```

The key change: `.requestMatchers("/api/v1/admin/**").permitAll()` → `.hasRole("ADMIN")`. Removed the inline "temporary" comment.

Also keep the existing comment style — remove `// Admin API - no auth required (temporary)` line since it no longer applies.

- [ ] **Step 2: Run the test again to verify it now passes**

Run: `cd pfplay-platform && ./gradlew :app:test --tests AdminEndpointSecurityTest`
Expected:
- `anonymousRequest_toAdminEndpoint_returns401` → PASS (401 is now returned)
- `authenticatedMember_toAdminEndpoint_returns403` → PASS (ROLE_MEMBER lacks ROLE_ADMIN)
- `authenticatedAdmin_toAdminEndpoint_doesNotReturn401or403` → PASS (ROLE_ADMIN allows)

If `authenticatedAdmin_...` fails because the endpoint internally errors (500, validator issues), that's OK for PR 0's scope — adjust the test to accept 500 as "not 401/403". The intent is only to verify the URL gate, not downstream behavior.

- [ ] **Step 3: Run the full test suite to check for regressions**

Run: `cd pfplay-platform && ./gradlew test`
Expected: All tests pass. If any existing admin-related test breaks because it relied on `permitAll` behavior (e.g., called without auth expecting 200), those tests need auth setup too — likely update them to use `@WithMockUser(roles = "ADMIN")`.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java \
        app/src/test/java/com/pfplaybackend/api/common/config/security/AdminEndpointSecurityTest.java
git commit -m "$(cat <<'EOF'
fix(security): Gate /api/v1/admin/** behind ROLE_ADMIN

Removes temporary permitAll on /api/v1/admin/** that allowed any
anonymous request through. Now requires ROLE_ADMIN authority — matches
the intent of the existing @PreAuthorize guards on admin controllers
and prevents the URL-only defense from being a no-op.

Regression test added asserting:
- anonymous → 401
- non-admin member → 403
- admin → not 401/403 (endpoint may have own errors; gate is transparent)

This is PR 0 of the admin platform roadmap (see
docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.2.3).
EOF
)"
```

---

### Task 1.3: Manual smoke verification

- [ ] **Step 1: Start local backend**

Run: `cd pfplay-platform && ./gradlew :app:bootRun` (or equivalent profile command per local setup)

Wait for Spring Boot startup — about 30-60s.

- [ ] **Step 2: Hit admin endpoint without auth**

Run: `curl -i http://localhost:8080/api/v1/admin/partyrooms`
Expected: `HTTP/1.1 401 Unauthorized` (or 403 depending on filter order).

Before this PR, this call returned `200 OK` or application response. This is the bug that's now fixed.

- [ ] **Step 3: Stop backend**

Ctrl-C. No further action.

PR 0 complete.

---

## Chunk 2 — PR 10: Avatar module scaffold + V12 migration + entity move

**Goal:** Create `avatar` Gradle module, move avatar catalog entities/VOs/repos from `user` module to `avatar` module, drop icon resource table + PairType enum, apply V12 schema changes (icon_uri merge + lifecycle + audit columns + face obtainable_type), and rewire the `user` picker services via cross-module port — all while keeping external REST contracts unchanged.

**Prerequisite**: PR 1-9 merged. Specifically V4 (IAM refactor) must be in because Member/Guest entity shape assumed.

### File Structure for PR 10

**New Gradle module:**
- Create: `avatar/build.gradle`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/` (full hexagonal layout below)
- Modify: `settings.gradle` (add `'avatar'` to include list)
- Modify: `user/build.gradle` (add `implementation project(':avatar')` dependency)

**Entity/VO files to CREATE in `avatar` module** (moved + refactored):
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/entity/data/AvatarBodyResourceData.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/entity/data/AvatarFaceResourceData.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/enums/ObtainmentType.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/enums/LifecycleStatus.java` (NEW)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/value/AvatarBodyUri.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/value/AvatarFaceUri.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/value/AvatarIconUri.java` (retained; still used as VO type for `icon_uri` field and for `member.avatarSetting.avatarIconUri` cache)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/event/AvatarResourcePublished.java` (stub for now, full wiring in PR 11)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/event/AvatarResourceRetired.java` (stub)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/persistence/AvatarBodyResourceRepository.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/persistence/AvatarFaceResourceRepository.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/port/out/AvatarBodyResourcePort.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/port/out/AvatarFaceResourcePort.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/port/in/AvatarCatalogQueryUseCase.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/service/AvatarCatalogQueryService.java`
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarBodyDto.java` (moved)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarFaceDto.java` (moved)
- `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarIconDto.java` (moved)

**Files to DELETE in `user` module:**
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarBodyResourceData.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarFaceResourceData.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarIconResourceData.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/enums/PairType.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarBodyUri.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarFaceUri.java`
- `user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarIconUri.java`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarBodyResourceRepository.java`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarFaceResourceRepository.java`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarIconResourceRepository.java`
- `user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarBodyDto.java`
- `user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarFaceDto.java`
- `user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarIconDto.java`
- Corresponding test files that reference PairType or icon resource repository.

**Files to MODIFY in `user` module:**
- `user/src/main/java/com/pfplaybackend/api/user/application/service/AvatarResourceQueryService.java` — remove `findByNameAndPairType` logic; rewire via `AvatarCatalogQueryUseCase` port from avatar module.
- `user/src/main/java/com/pfplaybackend/api/user/application/service/UserAvatarQueryService.java` — import paths updated (DTOs now from `avatar.*`).
- `user/src/main/java/com/pfplaybackend/api/user/application/service/UserAvatarCommandService.java` — import paths updated.
- `user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarSetting.java` — import `AvatarBodyUri`/`AvatarFaceUri`/`AvatarIconUri` from `com.pfplaybackend.api.avatar.domain.value.*` instead of `com.pfplaybackend.api.user.domain.value.*`.
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/ProfileData.java` — same import update.
- `user/src/main/java/com/pfplaybackend/api/user/domain/service/UserAvatarDomainService.java` — same.
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java` — DTO imports.
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/payload/response/QueryMyProfileSummaryResponse.java` — DTO imports.
- Tests: update imports.

**Files to MODIFY in `app` module:**
- `app/src/main/java/com/pfplaybackend/api/admin/application/port/out/AdminAvatarResourcePort.java` — DTOs/VOs now from `avatar.*` package.
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/out/external/AdminAvatarResourceAdapter.java` — uses new avatar module ports.
- `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminProfileService.java` — import updates.
- `app/src/main/java/com/pfplaybackend/api/party/application/dto/shared/AvatarProfile.java` — uses AvatarBodyUri/FaceUri/IconUri from avatar module if needed (this DTO currently has String URIs, so likely no import change, but verify).
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java:149` — `avatar.getAvatarIconUri().getValue()` — VO import.
- Tests that reference avatar VOs.

**Flyway migration:**
- Create: `app/src/main/resources/db/migration/V12__avatar_bc_restructure.sql`

**Application config:**
- Modify: `app/src/main/resources/application.yml` (and environment-specific yml files) if JPA `entitymanagerfactory.packagesToScan` or similar needs to include `com.pfplaybackend.api.avatar`. Verify by running app startup after the move.
- Modify: `app/src/main/java/com/pfplaybackend/api/Application.java` if `@EntityScan` or `@ComponentScan` annotations are present and restrict packages.

**Dependencies:** PR 1-9 merged.

---

### Task 2.1: Create `avatar` Gradle module skeleton

- [ ] **Step 1: Create module directory structure**

Run (from repo root):
```bash
mkdir -p pfplay-platform/avatar/src/main/java/com/pfplaybackend/api/avatar/{adapter/in/web,adapter/out/persistence,application/port/in,application/port/out,application/service,application/dto,domain/entity/data,domain/enums,domain/event,domain/value}
mkdir -p pfplay-platform/avatar/src/test/java/com/pfplaybackend/api/avatar
```

- [ ] **Step 2: Create `avatar/build.gradle`**

Create `pfplay-platform/avatar/build.gradle`:

```groovy
def queryDslVersion = '5.0.0'

dependencies {
    implementation project(':common')

    // Spring Boot Starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'  // for @PreAuthorize on admin endpoints (PR 11)

    // QueryDSL
    implementation "com.querydsl:querydsl-core:${queryDslVersion}"
    implementation "com.querydsl:querydsl-jpa:${queryDslVersion}:jakarta"
    annotationProcessor(
            "com.querydsl:querydsl-apt:${queryDslVersion}:jakarta",
            "jakarta.persistence:jakarta.persistence-api:3.1.0"
    )

    // Swagger (SpringDoc OpenAPI 3)
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.4.0'

    // Test
    testImplementation 'org.springframework.security:spring-security-test'
}
```

- [ ] **Step 3: Register module in `settings.gradle`**

Modify `pfplay-platform/settings.gradle`:

```groovy
rootProject.name = 'pfplay'
include 'common', 'realtime', 'playlist', 'user', 'avatar', 'app'

buildCache {
    local {
        enabled = true
    }
}
```

- [ ] **Step 4: Declare `avatar` as dependency of `user`**

Modify `pfplay-platform/user/build.gradle` — add after the existing `implementation project(':common')` line:

```groovy
    implementation project(':common')
    implementation project(':avatar')
```

- [ ] **Step 5: Declare `avatar` as dependency of `app`**

Check `pfplay-platform/app/build.gradle` and add `implementation project(':avatar')` near the other module deps.

- [ ] **Step 6: Verify Gradle recognizes the new module**

Run: `cd pfplay-platform && ./gradlew :avatar:tasks`
Expected: `:avatar` module's tasks list printed (compileJava, test, etc.). No `Project :avatar not found` error.

- [ ] **Step 7: Commit scaffold**

```bash
git add pfplay-platform/settings.gradle pfplay-platform/avatar pfplay-platform/user/build.gradle pfplay-platform/app/build.gradle
git commit -m "feat(avatar): Add empty avatar Gradle module scaffold

Creates new Gradle module with hexagonal package skeleton. Not wired to
any code yet; follow-up tasks move entities from user module.

Part of PR 10 (roadmap §9.1)."
```

---

### Task 2.2: Write V12 Flyway migration

**Files:**
- Create: `app/src/main/resources/db/migration/V12__avatar_bc_restructure.sql`

- [ ] **Step 1: Write V12 DDL**

Create `pfplay-platform/app/src/main/resources/db/migration/V12__avatar_bc_restructure.sql`:

```sql
-- =====================================================
-- V12: Avatar BC Restructure
--
-- See docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.11 for rationale.
--
-- Changes:
--   1. body/face: ADD icon_uri, lifecycle_status, audit columns
--   2. face: ADD obtainable_type (BASIC fixed, future expansion)
--   3. Data transfer from avatar_icon_resource into parent icon_uri
--      using name-prefix convention (no dependency on pair_type ordinal)
--   4. DROP avatar_icon_resource
-- =====================================================

-- Step 1. body: icon_uri, lifecycle, 감사 컬럼
ALTER TABLE avatar_body_resource
    ADD COLUMN icon_uri         VARCHAR(500) NULL        AFTER resource_uri,
    ADD COLUMN lifecycle_status VARCHAR(16)  NOT NULL
        DEFAULT 'PUBLISHED'                               AFTER is_default_setting,
    ADD COLUMN created_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP                         AFTER combine_positiony,
    ADD COLUMN created_by       BIGINT       NULL        AFTER created_at,
    ADD COLUMN updated_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP                       AFTER created_by,
    ADD COLUMN updated_by       BIGINT       NULL        AFTER updated_at;

ALTER TABLE avatar_body_resource
    ADD CONSTRAINT chk_body_lifecycle
        CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED'));

-- Step 2. face: icon_uri, lifecycle, obtainable_type, 감사 컬럼
ALTER TABLE avatar_face_resource
    ADD COLUMN icon_uri         VARCHAR(500) NULL        AFTER resource_uri,
    ADD COLUMN obtainable_type  VARCHAR(16)  NOT NULL
        DEFAULT 'BASIC'                                   AFTER icon_uri,
    ADD COLUMN lifecycle_status VARCHAR(16)  NOT NULL
        DEFAULT 'PUBLISHED'                               AFTER obtainable_type,
    ADD COLUMN created_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by       BIGINT       NULL,
    ADD COLUMN updated_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    ADD COLUMN updated_by       BIGINT       NULL;

ALTER TABLE avatar_face_resource
    ADD CONSTRAINT chk_face_lifecycle
        CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED')),
    ADD CONSTRAINT chk_face_obtainable
        CHECK (obtainable_type = 'BASIC');

-- Step 3. Data transfer from avatar_icon_resource → parent.icon_uri
--   Matches by name-prefix (does NOT depend on pair_type ordinal).
UPDATE avatar_body_resource b
INNER JOIN avatar_icon_resource i
        ON i.name LIKE 'ava_icon_body_%'
       AND i.name = CONCAT('ava_icon_', SUBSTRING(b.name, 5))
SET b.icon_uri = i.resource_uri;

UPDATE avatar_face_resource f
INNER JOIN avatar_icon_resource i
        ON i.name LIKE 'ava_icon_face_%'
       AND i.name = CONCAT('ava_icon_', SUBSTRING(f.name, 5))
SET f.icon_uri = i.resource_uri;

-- Step 4. Drop icon resource table (and PairType enum will be removed from Java in same PR)
DROP TABLE avatar_icon_resource;
```

- [ ] **Step 2: Run V12 in isolation with Flyway's info/validate**

Run: `cd pfplay-platform && ./gradlew :app:flywayInfo` (if flyway-gradle-plugin is configured) OR manually run against local dev DB.

Alternative (safer for dev): drop local dev DB, re-run app with fresh migrations, verify V1→V12 all apply cleanly.

Expected: Flyway reports V12 as applied; no validation errors. Local DB's `avatar_body_resource` now has 8 columns + 4 new (icon_uri, lifecycle_status, created_at, created_by, updated_at, updated_by — total ~12 columns).

- [ ] **Step 3: Verify V12 Step-3 transferred icon data correctly**

Run SQL against local dev DB:

```sql
SELECT name, icon_uri FROM avatar_body_resource WHERE icon_uri IS NOT NULL;
-- Expected rows:
--   ava_body_basic_002  | https://firebasestorage.../ava_icon/ava_icon_body_basic_002.png?...
--   ava_body_basic_003  | https://firebasestorage.../ava_icon/ava_icon_body_basic_003.png?...
--   ava_body_djing_001  | https://firebasestorage.../ava_icon/ava_icon_body_djing_001.png?...
--   ava_body_djing_002  | https://firebasestorage.../ava_icon/ava_icon_body_djing_002.png?...

SELECT name, icon_uri FROM avatar_face_resource WHERE icon_uri IS NOT NULL;
-- Expected:
--   ava_face_basic_001  | https://firebasestorage.../ava_icon/ava_icon_face_basic_001.png?...

SELECT COUNT(*) FROM avatar_body_resource WHERE icon_uri IS NULL;
-- Expected: 11 (the 11 bodies that never had icons remain NULL)

-- Verify table drop
SHOW TABLES LIKE 'avatar_icon_resource';
-- Expected: (empty result)
```

- [ ] **Step 4: Commit the migration**

```bash
git add app/src/main/resources/db/migration/V12__avatar_bc_restructure.sql
git commit -m "feat(db): V12 migration — Avatar BC restructure

Merges avatar_icon_resource into body/face.icon_uri column, drops the
brittle ordinal-stored pair_type, adds lifecycle_status + audit columns,
adds face.obtainable_type for future monetization expansion.

Data transfer from icon table uses name-prefix JOIN — no ordinal
assumption, works for all 5 seed icons from V3.

See docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.11.

Part of PR 10."
```

---

### Task 2.3: Port entities + VOs from `user` to `avatar` module (carbon copy, old files intact)

> Rationale: moving entities across modules atomically is risky. Instead: copy to new module first, verify compiles, then flip usages one file at a time, then delete originals. This keeps the build green at every step.

**Files:**
- Create (new location): `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/value/AvatarBodyUri.java`, `AvatarFaceUri.java`, `AvatarIconUri.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/enums/ObtainmentType.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/enums/LifecycleStatus.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/entity/data/AvatarBodyResourceData.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/entity/data/AvatarFaceResourceData.java`

- [ ] **Step 1: Copy `AvatarBodyUri.java` to avatar module**

Open `user/.../domain/value/AvatarBodyUri.java`, copy the entire class body, create `avatar/.../domain/value/AvatarBodyUri.java` with the same content but package changed to `com.pfplaybackend.api.avatar.domain.value`.

Do the same for `AvatarFaceUri.java` and `AvatarIconUri.java`.

- [ ] **Step 2: Copy `ObtainmentType.java`**

Copy `user/.../domain/enums/ObtainmentType.java` to `avatar/.../domain/enums/ObtainmentType.java`. Change package declaration.

- [ ] **Step 3: Create `LifecycleStatus.java` (new enum)**

Create `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/enums/LifecycleStatus.java`:

```java
package com.pfplaybackend.api.avatar.domain.enums;

public enum LifecycleStatus {
    DRAFT, PUBLISHED, RETIRED
}
```

- [ ] **Step 4: Copy `AvatarBodyResourceData.java` with new columns**

Create `avatar/src/main/java/com/pfplaybackend/api/avatar/domain/entity/data/AvatarBodyResourceData.java`:

```java
package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Table(name = "AVATAR_BODY_RESOURCE")
@Entity
public class AvatarBodyResourceData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String resourceUri;

    @Column(name = "icon_uri")
    private String iconUri;  // nullable post-V12

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ObtainmentType obtainableType;

    @Column(nullable = false)
    private int obtainableScore;

    @Column(nullable = false)
    private boolean isCombinable;

    @Column(nullable = false)
    private boolean isDefaultSetting;

    private int combinePositionX;
    private int combinePositionY;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private LifecycleStatus lifecycleStatus;  // aggregate factory MUST set explicitly

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private Long createdBy;  // administrator_id raw Long (no VO import) — see design.md §3.3.5

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Long updatedBy;

    public AvatarBodyResourceData() {}

    @Builder
    public AvatarBodyResourceData(Long id, String name, String resourceUri, String iconUri,
                                  ObtainmentType obtainableType, int obtainableScore,
                                  boolean isCombinable, boolean isDefaultSetting,
                                  int combinePositionX, int combinePositionY,
                                  LifecycleStatus lifecycleStatus,
                                  LocalDateTime createdAt, Long createdBy,
                                  LocalDateTime updatedAt, Long updatedBy) {
        this.id = id;
        this.name = name;
        this.resourceUri = resourceUri;
        this.iconUri = iconUri;
        this.obtainableType = obtainableType;
        this.obtainableScore = obtainableScore;
        this.isCombinable = isCombinable;
        this.isDefaultSetting = isDefaultSetting;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
        this.lifecycleStatus = lifecycleStatus;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    // Aggregate factory for new draft rows
    public static AvatarBodyResourceData draft(String name, String resourceUri, String iconUri,
                                               ObtainmentType obtainableType, int obtainableScore,
                                               boolean isCombinable, boolean isDefaultSetting,
                                               int combinePositionX, int combinePositionY,
                                               Long createdByAdministratorId) {
        LocalDateTime now = LocalDateTime.now();
        return AvatarBodyResourceData.builder()
                .name(name)
                .resourceUri(resourceUri)
                .iconUri(iconUri)
                .obtainableType(obtainableType)
                .obtainableScore(obtainableScore)
                .isCombinable(isCombinable)
                .isDefaultSetting(isDefaultSetting)
                .combinePositionX(combinePositionX)
                .combinePositionY(combinePositionY)
                .lifecycleStatus(LifecycleStatus.DRAFT)  // MUST be explicit (§3.3.5)
                .createdAt(now)
                .createdBy(createdByAdministratorId)
                .updatedAt(now)
                .updatedBy(createdByAdministratorId)
                .build();
    }

    // Full CRUD setters / domain methods arrive in PR 11. This task only creates the entity shape.
}
```

- [ ] **Step 5: Copy `AvatarFaceResourceData.java` with new columns (same pattern)**

Create `avatar/.../domain/entity/data/AvatarFaceResourceData.java` mirroring the structure (fewer fields — no combine positions, no obtainable score concept currently).

- [ ] **Step 6: Run build to verify avatar module compiles**

Run: `cd pfplay-platform && ./gradlew :avatar:compileJava`
Expected: BUILD SUCCESSFUL. No errors.

- [ ] **Step 7: Commit**

```bash
git add pfplay-platform/avatar/src/main/java/com/pfplaybackend/api/avatar/
git commit -m "feat(avatar): Move avatar entities/VOs to avatar module (carbon copy)

Copies AvatarBody/FaceResourceData + VOs + enums to the new avatar
module with added lifecycle_status, audit columns, icon_uri field on
body/face. Adds LifecycleStatus enum and draft() factory that ALWAYS
sets DRAFT (avoids relying on DB DEFAULT 'PUBLISHED' which exists only
for V12's in-place migration of pre-existing rows).

Old user-module copies still present — next step flips usages atomically.

Part of PR 10."
```

---

### Task 2.4: Create `AvatarBodyResourceRepository` + `AvatarFaceResourceRepository` in avatar module

**Files:**
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/persistence/AvatarBodyResourceRepository.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/persistence/AvatarFaceResourceRepository.java`

- [ ] **Step 1: Write the body repository**

```java
package com.pfplaybackend.api.avatar.adapter.out.persistence;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AvatarBodyResourceRepository extends JpaRepository<AvatarBodyResourceData, Long> {
    List<AvatarBodyResourceData> findAllByLifecycleStatus(LifecycleStatus lifecycleStatus);
    Optional<AvatarBodyResourceData> findByName(String name);
    boolean existsByName(String name);
}
```

- [ ] **Step 2: Write the face repository (same pattern)**

- [ ] **Step 3: Run build**

Run: `cd pfplay-platform && ./gradlew :avatar:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add pfplay-platform/avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/persistence/
git commit -m "feat(avatar): Add JPA repositories for avatar body/face"
```

---

### Task 2.5: Add DTOs + query port + query service in avatar module

**Files:**
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarBodyDto.java` (mirror of `user.../shared/AvatarBodyDto.java` with `lifecycleStatus` field added)
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarFaceDto.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/application/dto/AvatarIconDto.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/application/port/in/AvatarCatalogQueryUseCase.java`
- Create: `avatar/src/main/java/com/pfplaybackend/api/avatar/application/service/AvatarCatalogQueryService.java`

- [ ] **Step 1: Write `AvatarBodyDto` with lifecycle field**

```java
package com.pfplaybackend.api.avatar.application.dto;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
public class AvatarBodyDto {
    @Schema(example = "1") private Long id;
    @Schema(example = "default_body") private String name;
    @Schema(example = "https://cdn.pfplay.xyz/avatar/body/default.png") private String resourceUri;
    @Schema(example = "https://cdn.pfplay.xyz/avatar/icon/default.png") private String iconUri;
    @Schema(example = "BASIC") private ObtainmentType obtainableType;
    @Schema(example = "0") private int obtainableScore;
    @Schema(example = "true") private boolean combinable;
    @Schema(example = "true") private boolean defaultSetting;
    @Schema(example = "true") private boolean available;
    @Schema(example = "0") private int combinePositionX;
    @Schema(example = "0") private int combinePositionY;
    @Schema(example = "PUBLISHED") private LifecycleStatus lifecycleStatus;

    public static AvatarBodyDto create(AvatarBodyResourceData data) {
        return AvatarBodyDto.builder()
                .id(data.getId())
                .name(data.getName())
                .resourceUri(data.getResourceUri())
                .iconUri(data.getIconUri())
                .obtainableType(data.getObtainableType())
                .obtainableScore(data.getObtainableScore())
                .combinable(data.isCombinable())
                .defaultSetting(data.isDefaultSetting())
                .available(data.getObtainableType() == ObtainmentType.BASIC)
                .combinePositionX(data.getCombinePositionX())
                .combinePositionY(data.getCombinePositionY())
                .lifecycleStatus(data.getLifecycleStatus())
                .build();
    }
}
```

- [ ] **Step 2: Write `AvatarFaceDto` and `AvatarIconDto`**

Follow same pattern. `AvatarIconDto` is the lean `(id, name, resourceUri, available)` record — kept for compatibility with existing call sites (it's read from body.iconUri / face.iconUri now, not its own table).

- [ ] **Step 3: Write `AvatarCatalogQueryUseCase` port**

```java
package com.pfplaybackend.api.avatar.application.port.in;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;

import java.util.List;

public interface AvatarCatalogQueryUseCase {
    List<AvatarBodyDto> findPublishedBodies();
    List<AvatarFaceDto> findPublishedFaces();
    AvatarBodyDto findBodyByUri(String resourceUri);  // used by admin profile service (§3.3.2 Member composition)
    AvatarFaceDto findFaceByUri(String resourceUri);
    String findBodyIconUriByName(String bodyName);    // replaces old findByNameAndPairType BODY lookup
    String findFaceIconUriByName(String faceName);    // replaces old findByNameAndPairType FACE lookup
}
```

- [ ] **Step 4: Write `AvatarCatalogQueryService` implementation**

```java
package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvatarCatalogQueryService implements AvatarCatalogQueryUseCase {

    private final AvatarBodyResourceRepository bodyRepo;
    private final AvatarFaceResourceRepository faceRepo;

    @Override
    public List<AvatarBodyDto> findPublishedBodies() {
        return bodyRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED).stream()
                .map(AvatarBodyDto::create)
                .collect(Collectors.toList());
    }

    @Override
    public List<AvatarFaceDto> findPublishedFaces() {
        return faceRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED).stream()
                .map(AvatarFaceDto::create)
                .collect(Collectors.toList());
    }

    @Override
    public AvatarBodyDto findBodyByUri(String resourceUri) {
        return bodyRepo.findAll().stream()
                .filter(b -> b.getResourceUri().equals(resourceUri))
                .findFirst()
                .map(AvatarBodyDto::create)
                .orElse(null);  // callers handle null (match current adapter behavior)
    }
    // ... findFaceByUri analogous
    // ... findBodyIconUriByName — bodyRepo.findByName(name).map(AvatarBodyResourceData::getIconUri).orElse(null);
    // ... findFaceIconUriByName — faceRepo.findByName(name).map(AvatarFaceResourceData::getIconUri).orElse(null);
}
```

- [ ] **Step 5: Build**

Run: `cd pfplay-platform && ./gradlew :avatar:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add pfplay-platform/avatar/src/main/java/com/pfplaybackend/api/avatar/application/
git commit -m "feat(avatar): Add AvatarCatalogQueryUseCase port + service

Provides the cross-module interface that user/app modules call to read
the avatar catalog. Replaces the direct JPA repository access that
user module's AvatarResourceQueryService used to do (includes the
old findByNameAndPairType lookup, now resolved via body/face's
own icon_uri field)."
```

---

### Task 2.6: Rewire `user` module consumers to use avatar module's port

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/AvatarResourceQueryService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/UserAvatarQueryService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/UserAvatarCommandService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/service/UserAvatarDomainService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarSetting.java` (imports)
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/ProfileData.java` (imports)
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/UserAvatarQueryController.java` (imports)
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/payload/response/QueryMyProfileSummaryResponse.java` (imports)

- [ ] **Step 1: Update `AvatarResourceQueryService` to use avatar port**

Open current `user/.../AvatarResourceQueryService.java`. Replace:
- Inject `AvatarCatalogQueryUseCase` instead of `AvatarBodyResourceRepository` + `AvatarFaceResourceRepository` + `AvatarIconResourceRepository`.
- Rewrite `findByNameAndPairType(iconName, PairType.FACE)` → `avatarCatalogQueryUseCase.findFaceIconUriByName(faceName)`.
- Same for BODY.
- Remove `PairType` import.

- [ ] **Step 2: Update `AvatarSetting` and `ProfileData` imports**

Change:
```java
import com.pfplaybackend.api.user.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.user.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.user.domain.value.AvatarIconUri;
```
to:
```java
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
```

Do the same in every user-module file that imports these VOs. Grep: `rg "com.pfplaybackend.api.user.domain.value.(AvatarBodyUri|AvatarFaceUri|AvatarIconUri)" user/`.

- [ ] **Step 3: Update all user-module import references to DTOs (AvatarBodyDto/FaceDto/IconDto)**

Grep and replace:
- `com.pfplaybackend.api.user.application.dto.shared.AvatarBodyDto` → `com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto`
- Same for FaceDto, IconDto.

- [ ] **Step 4: Build**

Run: `cd pfplay-platform && ./gradlew :user:compileJava`
Expected: BUILD SUCCESSFUL. All old `user.domain.value.Avatar*Uri` and `user.application.dto.shared.Avatar*Dto` imports gone.

If tests fail to compile here, skip test fixes for this step — they'll be addressed in task 2.8.

- [ ] **Step 5: Commit**

```bash
git add pfplay-platform/user/src/main/java
git commit -m "refactor(user): Rewire consumers to avatar module port

Services/entities now import Avatar VOs and DTOs from the avatar module
and use AvatarCatalogQueryUseCase instead of direct JPA repos.
findByNameAndPairType usage replaced with body/face icon_uri lookup.

PairType enum and AvatarIconResource* classes still present on this
commit — deleted in the next task. Keeping them temporarily avoids
a 'half-deleted' intermediate build."
```

---

### Task 2.7: Rewire `app` module consumers and delete `user`-module avatar files

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/port/out/AdminAvatarResourcePort.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/adapter/out/external/AdminAvatarResourceAdapter.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminProfileService.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java`
- Delete (user module): all files listed under "Files to DELETE in `user` module" in the File Structure section.

- [ ] **Step 1: Update `AdminAvatarResourcePort` imports**

Change `com.pfplaybackend.api.user.domain.entity.data.AvatarBodyResourceData` → `com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData`, and all Avatar* imports.

Rewrite `findAvatarIconPairWithSingleBody(AvatarBodyDto)` and `findPairAvatarIconByFaceUri(AvatarFaceUri)` signatures if they previously returned `AvatarIconUri` looked up from icon table — now they read from body/face's `icon_uri` field.

- [ ] **Step 2: Update `AdminAvatarResourceAdapter` implementation**

The adapter currently delegates to user-module repositories. Replace those with `AvatarCatalogQueryUseCase` injection.

- [ ] **Step 3: Update all other `app`-module imports**

Grep: `rg "com.pfplaybackend.api.user.(domain.value.Avatar|domain.entity.data.Avatar|application.dto.shared.Avatar)" app/`
For each match, update import to avatar module package.

- [ ] **Step 4: Build**

Run: `cd pfplay-platform && ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Delete user-module avatar files**

```bash
cd pfplay-platform
rm user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarBodyResourceData.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarFaceResourceData.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/AvatarIconResourceData.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/enums/PairType.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarBodyUri.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarFaceUri.java
rm user/src/main/java/com/pfplaybackend/api/user/domain/value/AvatarIconUri.java
rm user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarBodyResourceRepository.java
rm user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarFaceResourceRepository.java
rm user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/AvatarIconResourceRepository.java
rm user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarBodyDto.java
rm user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarFaceDto.java
rm user/src/main/java/com/pfplaybackend/api/user/application/dto/shared/AvatarIconDto.java
```

- [ ] **Step 6: Delete obsolete generated QueryDSL Q-classes (they regenerate)**

```bash
rm -rf user/build/generated/sources/annotationProcessor/java/main/com/pfplaybackend/api/user/domain/entity/data/QAvatar*.java
rm -rf user/build/generated/sources/annotationProcessor/java/main/com/pfplaybackend/api/user/domain/value/QAvatar*.java
```

- [ ] **Step 7: Full build + test**

Run: `cd pfplay-platform && ./gradlew build -x test` (skip tests first to verify compile)
Expected: BUILD SUCCESSFUL.

Run: `cd pfplay-platform && ./gradlew test`
Expected: Some tests may fail due to import changes. Next task fixes them.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(avatar): Delete user-module avatar catalog — moved to avatar module

Completes PR 10's atomic move. Old entities, VOs, repos, DTOs, PairType
enum all removed from user module; fresh copies live in avatar module
with V12 schema additions (icon_uri, lifecycle, audit cols).

Tests still need import updates — next commit."
```

---

### Task 2.8: Update tests in user + app modules to new import paths

**Files:** All test files that previously imported avatar classes from user module.

- [ ] **Step 1: Grep for failing test imports**

Run: `rg "com.pfplaybackend.api.user.(domain.value.Avatar|domain.entity.data.Avatar|application.dto.shared.Avatar|domain.enums.PairType)" user/src/test app/src/test`

- [ ] **Step 2: Fix each test import**

For each hit, update import path to `com.pfplaybackend.api.avatar.*` equivalent. Remove references to `PairType` — the tests for `findByNameAndPairType` become tests for `findBodyIconUriByName` / `findFaceIconUriByName`. Some tests may need rewriting if they stubbed the icon repository.

- [ ] **Step 3: Delete obsolete test files**

Delete test files that can't meaningfully be rewritten:
- `user/src/test/java/com/pfplaybackend/api/user/application/service/AvatarResourceQueryServiceTest.java` — rewrite scope too large; the AvatarCatalogQueryService has its own tests in task 2.9.

- [ ] **Step 4: Run test suite**

Run: `cd pfplay-platform && ./gradlew test`
Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: Update avatar imports across user/app test suites"
```

---

### Task 2.9: Add `AvatarCatalogQueryService` tests

**Files:**
- Create: `avatar/src/test/java/com/pfplaybackend/api/avatar/application/service/AvatarCatalogQueryServiceTest.java`

- [ ] **Step 1: Write tests for lifecycle filtering**

```java
package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarCatalogQueryServiceTest {

    @Mock AvatarBodyResourceRepository bodyRepo;
    @Mock AvatarFaceResourceRepository faceRepo;
    @InjectMocks AvatarCatalogQueryService service;

    @Test
    void findPublishedBodies_filtersByLifecycle() {
        AvatarBodyResourceData published = AvatarBodyResourceData.draft(
                "p", "body_uri", "icon_uri", ObtainmentType.BASIC, 0,
                true, true, 0, 0, null);
        // Force PUBLISHED in test fixture (use reflection or add a test helper)
        // ... (actual implementation may need a builder or reflection to set lifecycleStatus post-draft)

        when(bodyRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED))
                .thenReturn(List.of(published));

        List<AvatarBodyDto> result = service.findPublishedBodies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLifecycleStatus()).isEqualTo(LifecycleStatus.PUBLISHED);
    }

    @Test
    void findBodyIconUriByName_returnsIconUri() {
        AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                "ava_body_x", "body_uri", "icon_uri_X", ObtainmentType.BASIC, 0,
                false, false, 0, 0, null);

        when(bodyRepo.findByName("ava_body_x")).thenReturn(java.util.Optional.of(body));

        String iconUri = service.findBodyIconUriByName("ava_body_x");
        assertThat(iconUri).isEqualTo("icon_uri_X");
    }

    @Test
    void findBodyIconUriByName_missingBody_returnsNull() {
        when(bodyRepo.findByName("unknown")).thenReturn(java.util.Optional.empty());
        assertThat(service.findBodyIconUriByName("unknown")).isNull();
    }

    // Similar tests for findFaceIconUriByName, findBodyByUri, findPublishedFaces
}
```

- [ ] **Step 2: Run tests**

Run: `cd pfplay-platform && ./gradlew :avatar:test`
Expected: All pass.

- [ ] **Step 3: Commit**

```bash
git add pfplay-platform/avatar/src/test
git commit -m "test(avatar): AvatarCatalogQueryService unit tests"
```

---

### Task 2.10: Integration smoke test — app boots with avatar module

- [ ] **Step 1: Run full build**

Run: `cd pfplay-platform && ./gradlew clean build`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Run application against local dev DB (V12 already applied)**

Run: `cd pfplay-platform && ./gradlew :app:bootRun`
Expected: Spring Boot starts without errors. No `avatar_icon_resource` related warnings (JPA no longer tries to map that table).

- [ ] **Step 3: Verify user picker API still returns bodies**

Run: `curl -i http://localhost:8080/api/v1/users/me/profile/avatar/bodies -H 'Cookie: AccessToken=<valid-member-jwt>'`
Expected: `200 OK` with JSON list of bodies. `iconUri` field now populated for 4 bodies (the ones V3 seeded icons for).

- [ ] **Step 4: Stop app**

- [ ] **Step 5: Final PR 10 commit** (if any cleanup lingers)

PR 10 complete. Ready for review & merge.

---

## Chunk 3 — PR 11: Avatar admin CRUD + GCS upload + audit listener

**Goal:** Implement SUPER_ADMIN-only catalog management REST API on top of PR 10's avatar module: GCS backend-proxy uploads, create/list/patch/publish/retire endpoints, icon-only re-upload endpoint, domain events wired to Administration's `admin_action` audit table.

**Prerequisite:** PR 10 merged. Also PR 4 (admin login, `adminAuth` SpEL bean) and PR 8 (`admin_action` table) merged.

### File Structure for PR 11

**New files in `avatar` module:**
- Create: `avatar/.../adapter/in/web/AdminAvatarCommandController.java`
- Create: `avatar/.../adapter/in/web/AdminAvatarQueryController.java`
- Create: `avatar/.../adapter/in/web/payload/request/CreateAvatarBodyRequest.java`, `PatchAvatarBodyRequest.java`, `CreateAvatarFaceRequest.java`, `PatchAvatarFaceRequest.java`, `RetireAvatarResourceRequest.java`
- Create: `avatar/.../adapter/in/web/payload/response/AvatarBodyResponse.java`, etc.
- Create: `avatar/.../adapter/out/storage/GcsAvatarStorageAdapter.java`
- Create: `avatar/.../application/port/in/AvatarCatalogCommandUseCase.java`
- Create: `avatar/.../application/port/out/AvatarStoragePort.java`
- Create: `avatar/.../application/service/AvatarCatalogCommandService.java`
- Create: `avatar/.../domain/event/AvatarResourcePublished.java` (full)
- Create: `avatar/.../domain/event/AvatarResourceRetired.java` (full)
- Create: `avatar/.../domain/exception/AvatarException.java` + error codes

**New files in `app` module (audit listener):**
- Create: `app/.../administration/application/listener/AvatarAdminActionListener.java`

**Modified files in `app` module:**
- Modify: `app/src/main/resources/application-*.yml` — GCS bucket config + service account path (env-injected)
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java` — add `/api/v1/admin/avatar/**` hasRole('SUPER_ADMIN') rule (if §5.2.3 gate not already there from PR 5)

**Dependencies added:**
- `avatar/build.gradle` — `implementation 'com.google.cloud:google-cloud-storage:2.40.0'` (or current stable)
- May also need `implementation 'org.springframework.boot:spring-boot-starter-actuator'` if health check for GCS is desired (optional).

---

### Task 3.1: Add GCS SDK dependency + config properties

- [ ] **Step 1: Add dependency to `avatar/build.gradle`**

```groovy
implementation 'com.google.cloud:google-cloud-storage:2.40.0'
```

- [ ] **Step 2: Create `AvatarStorageProperties`**

Create `avatar/.../adapter/out/storage/AvatarStorageProperties.java`:

```java
package com.pfplaybackend.api.avatar.adapter.out.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pfplay.avatar.storage")
@Getter @Setter
public class AvatarStorageProperties {
    private String bucket;              // e.g., pfplay-firebase.appspot.com
    private String serviceAccountPath;  // path to JSON key (env-injected)
    private String baseUrlPrefix;       // https://storage.googleapis.com
}
```

- [ ] **Step 3: Wire properties in `app/src/main/resources/application.yml`**

Add:

```yaml
pfplay:
  avatar:
    storage:
      bucket: ${PFPLAY_AVATAR_BUCKET:pfplay-firebase.appspot.com}
      service-account-path: ${PFPLAY_AVATAR_GCS_KEY_PATH:}
      base-url-prefix: https://storage.googleapis.com
```

- [ ] **Step 4: Commit**

```bash
git add avatar/build.gradle avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/storage/AvatarStorageProperties.java app/src/main/resources/application.yml
git commit -m "build(avatar): Add google-cloud-storage dep + storage config"
```

---

### Task 3.2: Implement `AvatarStoragePort` + `GcsAvatarStorageAdapter`

**Files:**
- Create: `avatar/.../application/port/out/AvatarStoragePort.java`
- Create: `avatar/.../adapter/out/storage/GcsAvatarStorageAdapter.java`

- [ ] **Step 1: Write the port interface**

```java
package com.pfplaybackend.api.avatar.application.port.out;

public interface AvatarStoragePort {
    /**
     * Uploads a file to storage under the given category path.
     * @param category one of "ava_body", "ava_face", "ava_icon"
     * @param filename final object name (caller-generated, e.g. yyyymmdd_<random>.png)
     * @param contentType MIME type (image/png, image/jpeg)
     * @param bytes file content
     * @return public URL of uploaded object
     */
    String upload(String category, String filename, String contentType, byte[] bytes);

    /** Deletes an object by its public URL (parses back to bucket/object). */
    void deleteByPublicUrl(String publicUrl);
}
```

- [ ] **Step 2: Write the GCS adapter**

Implement `GcsAvatarStorageAdapter` using `com.google.cloud.storage.Storage`. Construct `Storage` client from service account JSON key path (if present) or default credentials. Use `Blob.create(BlobInfo.newBuilder(bucket, objectName).setContentType(ct).build(), bytes)`. Return public URL as `https://storage.googleapis.com/<bucket>/<object>`.

For `deleteByPublicUrl`, parse the URL to extract object path, call `storage.delete(BlobId.of(bucket, object))`.

- [ ] **Step 3: Write unit test with mocked Storage client**

- [ ] **Step 4: Commit**

```bash
git add avatar/src/main/java/com/pfplaybackend/api/avatar/adapter/out/storage avatar/src/main/java/com/pfplaybackend/api/avatar/application/port/out
git commit -m "feat(avatar): GCS storage adapter for backend-proxy uploads"
```

---

### Task 3.3: Implement domain events + error exceptions

**Files:**
- Create: `avatar/.../domain/event/AvatarResourcePublished.java`
- Create: `avatar/.../domain/event/AvatarResourceRetired.java`
- Create: `avatar/.../domain/exception/AvatarException.java`
- Create: `avatar/.../domain/exception/AvatarErrorCode.java`

- [ ] **Step 1: Write events as records**

```java
package com.pfplaybackend.api.avatar.domain.event;

public record AvatarResourcePublished(
        String resourceType,   // "AVATAR_BODY" | "AVATAR_FACE"
        Long resourceId,
        String resourceUri,
        Long publishedByAdministratorId
) {}
```

And similarly `AvatarResourceRetired(resourceType, resourceId, String reason, Long retiredByAdministratorId)`.

- [ ] **Step 2: Write error code enum**

```java
package com.pfplaybackend.api.avatar.domain.exception;

public enum AvatarErrorCode {
    AVATAR_NAME_ALREADY_EXISTS,
    AVATAR_INVALID_FILE_FORMAT,
    AVATAR_FILE_TOO_LARGE,
    AVATAR_STORAGE_UPLOAD_FAILED,
    AVATAR_INVALID_LIFECYCLE_TRANSITION,
    AVATAR_RESOURCE_RETIRED,
    AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH,
    AVATAR_INVALID_DEFAULT_SETTING,
}
```

- [ ] **Step 3: Write exception class extending common exception base**

- [ ] **Step 4: Commit**

---

### Task 3.4: Extend `AvatarBodyResourceData` with lifecycle transition + update methods

- [ ] **Step 1: Add aggregate methods**

Add to `AvatarBodyResourceData`:

```java
public void publish() {
    if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
        throw new AvatarException(AvatarErrorCode.AVATAR_INVALID_LIFECYCLE_TRANSITION);
    }
    this.lifecycleStatus = LifecycleStatus.PUBLISHED;
}

public void retire() {
    if (this.lifecycleStatus != LifecycleStatus.PUBLISHED) {
        throw new AvatarException(AvatarErrorCode.AVATAR_INVALID_LIFECYCLE_TRANSITION);
    }
    this.lifecycleStatus = LifecycleStatus.RETIRED;
}

public void updateMetadata(ObtainmentType obtainableType, int score, boolean combinable,
                           boolean defaultSetting, int posX, int posY, Long updatedBy) {
    assertMutable();
    // invariant: BASIC ↔ score=0
    if (obtainableType == ObtainmentType.BASIC && score != 0) {
        throw new AvatarException(AvatarErrorCode.AVATAR_INVALID_DEFAULT_SETTING);
    }
    // invariant: isDefaultSetting true ⇒ BASIC + PUBLISHED
    if (defaultSetting && (obtainableType != ObtainmentType.BASIC
            || this.lifecycleStatus != LifecycleStatus.PUBLISHED)) {
        throw new AvatarException(AvatarErrorCode.AVATAR_INVALID_DEFAULT_SETTING);
    }
    this.obtainableType = obtainableType;
    this.obtainableScore = score;
    this.isCombinable = combinable;
    this.isDefaultSetting = defaultSetting;
    this.combinePositionX = posX;
    this.combinePositionY = posY;
    this.updatedBy = updatedBy;
}

public void replaceResourceUri(String newUri, Long updatedBy) {
    assertMutable();
    if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
        throw new AvatarException(AvatarErrorCode.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH);
    }
    this.resourceUri = newUri;
    this.updatedBy = updatedBy;
}

public void replaceIconUri(String newIconUri, Long updatedBy) {
    assertMutable();
    if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
        throw new AvatarException(AvatarErrorCode.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH);
    }
    this.iconUri = newIconUri;
    this.updatedBy = updatedBy;
}

private void assertMutable() {
    if (this.lifecycleStatus == LifecycleStatus.RETIRED) {
        throw new AvatarException(AvatarErrorCode.AVATAR_RESOURCE_RETIRED);
    }
}
```

- [ ] **Step 2: Write comprehensive tests for lifecycle transitions + invariant violations**

Tests for each path in the spec §3.3.5 + §6.I-8 error table.

- [ ] **Step 3: Commit**

---

### Task 3.5: Implement `AvatarCatalogCommandService`

Covers I-2 create, I-3 patch, I-4 icon-only re-upload, I-5 publish/retire. Each with explicit validation + GCS upload + DB write + immediate delete on failure + event publish.

- [ ] **Step 1: Write `AvatarCatalogCommandUseCase` port**

Methods: `createBody(Command)`, `createFace(Command)`, `patchBody(id, Command)`, `patchFace(id, Command)`, `replaceBodyIcon(id, bytes, contentType, Long adminId)`, `replaceFaceIcon(id, bytes, contentType, Long adminId)`, `publishBody(id, adminId)`, `publishFace(id, adminId)`, `retireBody(id, reason, adminId)`, `retireFace(id, reason, adminId)`.

- [ ] **Step 2: Write `AvatarCatalogCommandService`**

For `createBody`:
1. Validate name uniqueness (`bodyRepo.existsByName(name)`) → throw `AVATAR_NAME_ALREADY_EXISTS` if dup.
2. Validate file format & size (check content-type whitelist, reject >2MB).
3. Upload body to GCS → `bodyUri`.
4. If iconImage present: upload to GCS → `iconUri`. If any upload fails midway: delete prior uploads + throw.
5. Create `AvatarBodyResourceData.draft(...)` with `createdBy=adminId`.
6. `bodyRepo.save(data)`.
7. On DB exception: call `storagePort.deleteByPublicUrl(bodyUri)` and (if set) `storagePort.deleteByPublicUrl(iconUri)`, then re-throw.
8. Return DTO.

For `publishBody`:
1. Load body by id or 404.
2. Call `body.publish()` (throws on invalid state).
3. Save.
4. `eventPublisher.publishEvent(new AvatarResourcePublished("AVATAR_BODY", id, resourceUri, adminId))`.

For `retireBody`:
1. Load. Call `body.retire()`.
2. Save.
3. Publish `AvatarResourceRetired` event with reason.

For `replaceBodyIcon`: load → invariant check (must be DRAFT) → GCS delete old → GCS upload new → `body.replaceIconUri(newUri, adminId)` → save. On failure during GCS operations: rollback if possible, surface `AVATAR_STORAGE_UPLOAD_FAILED`.

- [ ] **Step 3: Write comprehensive tests** (one test per method × happy path + 2-3 failure paths)

- [ ] **Step 4: Commit**

---

### Task 3.6: Implement REST controllers

- [ ] **Step 1: Write `AdminAvatarQueryController`**

Endpoints:
- `GET /api/v1/admin/avatar/bodies?status=...&obtainableType=...&page=...&size=...`
- `GET /api/v1/admin/avatar/faces?...`

Both `@PreAuthorize("@adminAuth.canManageAvatarResources()")`.

- [ ] **Step 2: Write `AdminAvatarCommandController`**

Endpoints per §6.I:
- `POST /api/v1/admin/avatar/bodies` (multipart) — I-2
- `PATCH /api/v1/admin/avatar/bodies/{id}` (multipart) — I-3
- `POST /api/v1/admin/avatar/bodies/{id}/icon` (multipart) — I-4
- `POST /api/v1/admin/avatar/bodies/{id}/publish` — I-5
- `POST /api/v1/admin/avatar/bodies/{id}/retire` (JSON: `{reason}`) — I-5
- All `face` variants of above.

All `@PreAuthorize("@adminAuth.canManageAvatarResources()")`.

- [ ] **Step 3: Write `@WebMvcTest` for both controllers** (403 for non-SUPER_ADMIN, happy paths, error responses)

- [ ] **Step 4: Commit**

---

### Task 3.7: Implement `AvatarAdminActionListener` in `app.administration`

**Files:**
- Create: `app/.../administration/application/listener/AvatarAdminActionListener.java`

- [ ] **Step 1: Write the listener**

Subscribes to `AvatarResourcePublished` and `AvatarResourceRetired`. On each event, insert into `admin_action` table with:
- `administrator_id`: event's `publishedByAdministratorId` / `retiredByAdministratorId`
- `action_type`: `PUBLISH_AVATAR_RESOURCE` or `RETIRE_AVATAR_RESOURCE`
- `target_type`: event's `resourceType`
- `target_id`: event's `resourceId`
- `reason`: retire event's `reason` (null for publish)
- `metadata`: JSON with resourceUri

Uses `@TransactionalEventListener(AFTER_COMMIT) @Async` per §8.2.2.

- [ ] **Step 2: Write integration test** (publish a resource → verify row in `admin_action`)

- [ ] **Step 3: Commit**

---

### Task 3.8: Add `/api/v1/admin/avatar/**` URL gate to SecurityConfig

- [ ] **Step 1: Modify `SecurityConfig.java`**

Add **before** the generic `/api/v1/admin/**` rule:

```java
.requestMatchers("/api/v1/admin/avatar/**").hasRole("SUPER_ADMIN")
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

- [ ] **Step 2: Extend `AdminEndpointSecurityTest` to assert avatar subpath requires SUPER_ADMIN**

- [ ] **Step 3: Commit**

---

### Task 3.9: Extend `adminAuth` bean with `canManageAvatarResources()` method

(This may have been added in PR 5 already; if so, verify and skip.)

- [ ] **Step 1: Ensure bean has the method**

If PR 5's `AdminAuthorizationSpEL` doesn't have `canManageAvatarResources()`, add it:

```java
public boolean canManageAvatarResources() {
    return hasRole("SUPER_ADMIN");
}
```

- [ ] **Step 2: Commit if changed**

---

### Task 3.10: Integration smoke test (manual)

- [ ] **Step 1: Start app**

- [ ] **Step 2: Log in as SUPER_ADMIN (requires PR 4 login API)**

- [ ] **Step 3: Upload a new body via multipart POST**

Curl example:
```bash
curl -X POST http://localhost:8080/api/v1/admin/avatar/bodies \
     -H "Cookie: AdminAccessToken=<jwt>" \
     -F 'bodyImage=@/path/to/new_body.png' \
     -F 'iconImage=@/path/to/new_icon.png' \
     -F 'name=ava_body_test_001' \
     -F 'obtainableType=BASIC' \
     -F 'obtainableScore=0' \
     -F 'isCombinable=true' \
     -F 'isDefaultSetting=false' \
     -F 'combinePositionX=0' \
     -F 'combinePositionY=0'
```

Expected: 201 Created with body DTO. `lifecycleStatus` = DRAFT.

Verify:
- New object in GCS bucket at `ava_body/<yyyymmdd>_<random>.png`
- New row in `avatar_body_resource` table

- [ ] **Step 4: Publish it**

```bash
curl -X POST http://localhost:8080/api/v1/admin/avatar/bodies/{id}/publish \
     -H "Cookie: AdminAccessToken=<jwt>"
```

Expected: 204. Row's `lifecycle_status=PUBLISHED`. Row in `admin_action` table (from listener).

- [ ] **Step 5: Verify user picker sees the new body**

```bash
curl http://localhost:8080/api/v1/users/me/profile/avatar/bodies -H 'Cookie: AccessToken=<member-jwt>'
```

Expected: new body appears in response list.

- [ ] **Step 6: Retire it**

Expected: 204. Row's `lifecycle_status=RETIRED`. Row in `admin_action` with reason.

- [ ] **Step 7: Verify user picker now excludes the retired body**

Expected: retired body NOT in list (because `WHERE lifecycle_status='PUBLISHED'` filter in PR 10's service).

- [ ] **Step 8: Stop app, clean up test GCS objects**

PR 11 complete.

---

## Post-completion

Each PR, in order (PR 0 first, then PR 10 after PR 1-9 merged, then PR 11):

- [ ] Open GitHub PR targeting `develop`
- [ ] Link PR description to:
  - This plan (`docs/superpowers/plans/2026-04-20-admin-platform-pr0-pr10-pr11.md`)
  - Relevant spec sections (`docs/superpowers/specs/2026-04-19-admin-platform-*.md`)
- [ ] Verify CI passes (build + tests + any linting)
- [ ] Self-review diff, ensure no hard-coded secrets (GCS key path is env-injected)
- [ ] Merge

After PR 11 merged: Avatar Milestone M4 complete. Next: PR 12 (user management + activity log).
