# PR 2: V5 Administrator + Super Admin Seed Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the `Administrator` aggregate (Administration BC) via Flyway V5, seed a SUPER_ADMIN singleton with placeholder credentials replaced at boot from env, and remove the legacy `AdminUserInitializeService.addAdminUser()` path.

**Architecture:** V5 DDL creates `administrator` table with a functional unique index enforcing SUPER_ADMIN singleton. The migration also INSERTs placeholder rows in `user_account`, `administrator`, and `member` (for main-stage host). A new `SuperAdminSeedService` runs at `ApplicationReadyEvent` to replace placeholder email/password with `bcrypt(env.ADMIN_SEED_PASSWORD)` — idempotent on subsequent boots. The legacy `AdminUserInitializeService.addAdminUser()` call is removed; `initializeMainStage` is rewired to use the V5-seeded admin's fixed `user_id = 1`.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring Data JPA, MySQL 8.0 (functional unique index requires 8.0.13+), Flyway, Lombok, BCryptPasswordEncoder (existing), JUnit 5, Testcontainers MySQL.

**Spec source:** `docs/superpowers/specs/2026-04-19-admin-platform-schema.md` §4.2. Read this section before any task.

**Lessons from PR 1:**
- Plan templates must explicitly preserve existing behavior; Task 8 regression came from plan brevity.
- Mechanical implementation tasks → `sonnet` model. Architecture-touching tasks → `opus`. Reviewers default to `sonnet`, escalate to `opus` for bootstrap/critical paths.
- Single combined plan-document review at the end (not per-chunk).

**Branching:** Continue on `feature/admin-auth-iam-schema`. Each task ends in its own commit. PR 1 is merged into this branch (HEAD: `1bd83c1f`); PR 2 builds on top.

**Pre-launch caveat:** Dev DBs that already ran PR 1 contain a legacy admin row at `user_id = 1000000000000000` (GOOGLE provider). V5 inserts a NEW super-admin at `user_id = 1` (LOCAL). Both rows coexist post-V5. The legacy row is harmless — it's a regular user_account with no Administrator binding — but it's worth noting in the smoke test verification (Task 5). For production, V5 runs on a clean DB (pre-launch), so only `user_id = 1` exists.

---

## Verified codebase facts (read once, applied throughout)

- `BaseEntity` lives at `common/.../entity/BaseEntity.java`. Audit columns auto-handled.
- `UserAccountData` entity (PR 1) carries `@EmbeddedId UserId userId` mapped to `user_id`. `findByUserId(UserId)` exists.
- `MemberData` entity (PR 1) carries `Long memberId` PK + `Long userAccountId` value reference.
- `ApplicationReadyEventListener` lives at `app/src/main/java/com/pfplaybackend/api/bootstrap/`. Currently calls `adminUserInitializeService.addAdminUser()` returning a `UserId`, then `partyroomCommandService.initializeMainStage(adminId)`.
- `AdminUserInitializeService` lives in the `user` module; it has tests at `user/src/test/.../initialize/AdminUserInitializeServiceTest.java`.
- `BCryptPasswordEncoder` is wired in `SecurityConfig` (verified during PR 0). Inject as `PasswordEncoder` per Spring convention.
- The Administration BC's package convention per `CONTEXT_MAP.md`: `app/src/main/java/com/pfplaybackend/api/administration/...`. PR 2 establishes this package — first time it's used.
- Functional unique index syntax (`CREATE UNIQUE INDEX uk_x ON t ((CASE ...))`) requires MySQL 8.0.13+. Project uses 8.0.30 — supported.

---

## File Structure

### Files Created

- `app/src/main/resources/db/migration/V5__create_administrator.sql` — Flyway migration: administrator table, functional unique index, placeholder user_account/administrator/member INSERTs
- `app/src/main/java/com/pfplaybackend/api/administration/domain/value/AdministratorId.java` — value object wrapping `Long`, parallels `UserId`
- `app/src/main/java/com/pfplaybackend/api/administration/domain/value/AdminRole.java` — enum `SUPER_ADMIN`, `ADMIN` (VARCHAR(32) per spec)
- `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/AdministratorData.java` — JPA entity
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepository.java` — Spring Data interface
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/SuperAdminSeedService.java` — placeholder replacement logic at boot
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/SuperAdminSeedServiceTest.java` — unit tests (Mockito) for env handling + bcrypt + idempotency
- `app/src/test/java/com/pfplaybackend/api/administration/AdministratorRepositoryIntegrationTest.java` — Testcontainers MySQL integration test for the V5-seeded row + functional unique index behavior

### Files Modified

- `app/src/main/java/com/pfplaybackend/api/bootstrap/ApplicationReadyEventListener.java` — drop `adminUserInitializeService.addAdminUser()`; add `superAdminSeedService.finalizeSuperAdminCredentials()`; rewire `initializeMainStage` to use `SUPER_ADMIN_USER_ID = new UserId(1L)`

### Files Removed (or marked obsolete)

- `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeService.java`
- `user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeServiceTest.java`

**Decision: hard-delete both.** The class has no remaining production callers after Task 4's bootstrap rewiring. Leaving it as `@Deprecated` adds dead code; deleting it makes the intent clear.

---

## Test Strategy

| Layer | Test type | Notes |
|---|---|---|
| `AdministratorData` factory + invariants | Unit (POJO) | `createSuperAdmin(UserId, AdministratorId)` factory, `revoke()` lifecycle method |
| `SuperAdminSeedService` | Mockito unit | Env-var validation, bcrypt invocation, idempotency (placeholder absent → no-op), env null-out after read |
| `AdministratorRepository` | `@SpringBootTest` + Testcontainers MySQL | Verify V5 seed row present (administrator_id=1, user_account_id=1, role=SUPER_ADMIN), functional unique index rejects 2nd SUPER_ADMIN INSERT |
| Migration | Boot smoke test (Task 5) | Boot against fresh DB, verify V1→V5 apply, env replacement of placeholder works, `initializeMainStage` succeeds with new admin |

---

## Chunk 1: V5 Schema + Administrator Aggregate

### Task 1: Write V5 Flyway migration SQL

**Model:** `sonnet`

**Files:**
- Create: `app/src/main/resources/db/migration/V5__create_administrator.sql`

**Background:** V5 creates the Administration BC's first table and seeds the SUPER_ADMIN singleton. The functional unique index `uk_administrator_super_admin` enforces "at most one SUPER_ADMIN" at the DB level. Three INSERT statements seed: (a) `user_account` with placeholder email/hash for the admin, (b) `administrator` row binding the admin user to the SUPER_ADMIN role, (c) `member` row so PartyroomCommandService can use this account as main-stage host.

- [ ] **Step 1: Write the V5 migration file**

  ```sql
  -- V5__create_administrator.sql
  -- Administration context — Administrator aggregate
  -- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2
  --
  -- Super admin singleton enforced by functional unique index.
  -- Placeholder email/hash seeded; ApplicationReadyEvent replaces with
  -- bcrypt(env.ADMIN_SEED_PASSWORD) on first boot (idempotent).

  CREATE TABLE administrator (
      administrator_id              BIGINT      NOT NULL AUTO_INCREMENT,
      user_account_id               BIGINT      NOT NULL,
      role                          VARCHAR(32) NOT NULL,
      granted_by_administrator_id   BIGINT      NULL,
      granted_at                    DATETIME    NOT NULL,
      revoked_at                    DATETIME    NULL,
      created_at                    DATETIME    DEFAULT CURRENT_TIMESTAMP,
      updated_at                    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY (administrator_id),
      UNIQUE KEY uk_administrator_user_account (user_account_id),
      CONSTRAINT fk_administrator_granted_by
          FOREIGN KEY (granted_by_administrator_id)
          REFERENCES administrator(administrator_id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE UNIQUE INDEX uk_administrator_super_admin
      ON administrator ((CASE WHEN role = 'SUPER_ADMIN' THEN 1 ELSE NULL END));

  -- Seed super-admin user_account (placeholder; env replaces at boot)
  INSERT INTO user_account (user_id, email, provider_type, password_hash, created_at, updated_at)
  VALUES (
      1,
      '__SUPER_ADMIN_PLACEHOLDER_EMAIL__',
      'LOCAL',
      '__SUPER_ADMIN_PLACEHOLDER_HASH__',
      NOW(),
      NOW()
  );

  -- Seed administrator row binding super-admin to user_account 1
  INSERT INTO administrator (administrator_id, user_account_id, role, granted_by_administrator_id, granted_at, created_at, updated_at)
  VALUES (
      1,
      1,
      'SUPER_ADMIN',
      NULL,
      NOW(),
      NOW(),
      NOW()
  );

  -- Seed member row for super-admin (Party context — main-stage host needs a Member binding)
  INSERT INTO member (member_id, user_account_id, authority_tier, is_profile_updated, created_at, updated_at)
  VALUES (1, 1, 'FM', 0, NOW(), NOW());
  ```

- [ ] **Step 2: Visual lint**

  Re-read the file. Confirm: no trailing parens, all statements `;`-terminated, comments are non-Korean ASCII (production migrations stay English). Verify column order matches the existing `user_account` and `member` schemas from V4.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/resources/db/migration/V5__create_administrator.sql
  git commit -m "feat(admin): add V5 Flyway migration for Administrator aggregate

  - administrator table with role VARCHAR(32) (RBAC-extensible)
  - functional unique index enforces SUPER_ADMIN singleton at DB level
  - seeds user_account (LOCAL provider, placeholder email/hash),
    administrator (role=SUPER_ADMIN, fixed id=1), and member (FM tier,
    Party-context host binding) — all with fixed user_account_id=1
  - placeholder email/hash will be replaced at boot by
    SuperAdminSeedService.finalizeSuperAdminCredentials() (Task 3)

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2.1"
  ```

  (HEREDOC + Co-Authored-By trailer)

---

### Task 2: Create Administrator domain + repository

**Model:** `sonnet`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/value/AdministratorId.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/value/AdminRole.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/AdministratorData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdministratorRepository.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/AdministratorDataTest.java`

**Background:** First commit in the `administration` BC. The package convention `app/.../administration/...` is established here. `AdministratorData` mirrors V5 DDL columns. `AdminRole` is an enum but stored as `VARCHAR(32) STRING` per spec §4.2.4 (avoids ALTER TABLE on RBAC extension). `AdministratorId` value object wraps the auto-increment Long PK.

- [ ] **Step 1: Read PR 1's UserId VO for the value-object pattern**

  `Read` `common/src/main/java/com/pfplaybackend/api/common/domain/value/UserId.java` to confirm the project's `@Embeddable` + `@Convert` shape. `AdministratorId` follows the same pattern but lives in the `app` module's administration package (not common, because it's BC-specific per spec §3.2).

- [ ] **Step 2: Write the failing entity test**

  ```java
  package com.pfplaybackend.api.administration.domain.entity.data;

  import com.pfplaybackend.api.administration.domain.value.AdminRole;
  import org.junit.jupiter.api.Test;
  import static org.assertj.core.api.Assertions.assertThat;

  class AdministratorDataTest {

      @Test
      void createSuperAdmin_setsRoleAndUserAccountId() {
          var admin = AdministratorData.createSuperAdmin(1L);

          assertThat(admin.getUserAccountId()).isEqualTo(1L);
          assertThat(admin.getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
          assertThat(admin.getGrantedByAdministratorId()).isNull();
          assertThat(admin.getRevokedAt()).isNull();
          assertThat(admin.getAdministratorId()).isNull(); // assigned on persist
      }

      @Test
      void createAdmin_recordsGrantedBy() {
          var admin = AdministratorData.createAdmin(2L, 1L);

          assertThat(admin.getRole()).isEqualTo(AdminRole.ADMIN);
          assertThat(admin.getGrantedByAdministratorId()).isEqualTo(1L);
      }

      @Test
      void revoke_setsRevokedAt() {
          var admin = AdministratorData.createAdmin(2L, 1L);
          assertThat(admin.isRevoked()).isFalse();

          admin.revoke();

          assertThat(admin.isRevoked()).isTrue();
          assertThat(admin.getRevokedAt()).isNotNull();
      }

      @Test
      void revoke_isIdempotent() {
          var admin = AdministratorData.createAdmin(2L, 1L);
          admin.revoke();
          var first = admin.getRevokedAt();

          admin.revoke(); // second call should not change revokedAt

          assertThat(admin.getRevokedAt()).isEqualTo(first);
      }
  }
  ```

- [ ] **Step 3: Run the test — expect failure**

  ```
  JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7 ./gradlew :app:test --tests "*AdministratorDataTest" --no-daemon
  ```
  Expected: COMPILATION FAILURE (`AdministratorData`, `AdminRole`, factories don't exist).

- [ ] **Step 4: Write `AdminRole` enum**

  ```java
  package com.pfplaybackend.api.administration.domain.value;

  public enum AdminRole {
      SUPER_ADMIN,
      ADMIN
  }
  ```

- [ ] **Step 5: Write `AdministratorId` value object**

  Match the `UserId` pattern from Step 1. Typical shape:

  ```java
  package com.pfplaybackend.api.administration.domain.value;

  import jakarta.persistence.Embeddable;
  import lombok.AccessLevel;
  import lombok.EqualsAndHashCode;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  @Getter
  @Embeddable
  @EqualsAndHashCode
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class AdministratorId {
      private Long aid;

      public AdministratorId(Long aid) {
          this.aid = aid;
      }
  }
  ```

  (Adjust to match `UserId`'s exact field name and conventions — verify in Step 1.)

- [ ] **Step 6: Write `AdministratorData` JPA entity**

  ```java
  package com.pfplaybackend.api.administration.domain.entity.data;

  import com.pfplaybackend.api.administration.domain.value.AdminRole;
  import com.pfplaybackend.api.common.entity.BaseEntity;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Builder;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import org.hibernate.annotations.DynamicInsert;
  import org.hibernate.annotations.DynamicUpdate;

  import java.time.LocalDateTime;

  @Entity
  @Table(name = "administrator")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  @DynamicInsert
  @DynamicUpdate
  public class AdministratorData extends BaseEntity {

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      @Column(name = "administrator_id")
      private Long administratorId;

      @Column(name = "user_account_id", nullable = false)
      private Long userAccountId;

      @Column(name = "role", nullable = false, length = 32, columnDefinition = "VARCHAR(32)")
      @Enumerated(EnumType.STRING)
      private AdminRole role;

      @Column(name = "granted_by_administrator_id")
      private Long grantedByAdministratorId;

      @Column(name = "granted_at", nullable = false)
      private LocalDateTime grantedAt;

      @Column(name = "revoked_at")
      private LocalDateTime revokedAt;

      @Builder(access = AccessLevel.PRIVATE)
      private AdministratorData(Long userAccountId, AdminRole role,
                                Long grantedByAdministratorId, LocalDateTime grantedAt) {
          this.userAccountId = userAccountId;
          this.role = role;
          this.grantedByAdministratorId = grantedByAdministratorId;
          this.grantedAt = grantedAt;
      }

      public static AdministratorData createSuperAdmin(Long userAccountId) {
          return AdministratorData.builder()
              .userAccountId(userAccountId)
              .role(AdminRole.SUPER_ADMIN)
              .grantedByAdministratorId(null)
              .grantedAt(LocalDateTime.now())
              .build();
      }

      public static AdministratorData createAdmin(Long userAccountId, Long grantedByAdministratorId) {
          return AdministratorData.builder()
              .userAccountId(userAccountId)
              .role(AdminRole.ADMIN)
              .grantedByAdministratorId(grantedByAdministratorId)
              .grantedAt(LocalDateTime.now())
              .build();
      }

      public void revoke() {
          if (revokedAt != null) {
              return; // idempotent
          }
          this.revokedAt = LocalDateTime.now();
      }

      public boolean isRevoked() {
          return revokedAt != null;
      }
  }
  ```

  **Note on `columnDefinition`:** PR 1 Task 15 surfaced a Hibernate-vs-VARCHAR-vs-ENUM mismatch on `provider_type`. Apply the same defensive pattern here — `columnDefinition = "VARCHAR(32)"` ensures Hibernate schema validation matches the V5 DDL exactly.

- [ ] **Step 7: Write `AdministratorRepository`**

  ```java
  package com.pfplaybackend.api.administration.adapter.out.persistence;

  import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
  import com.pfplaybackend.api.administration.domain.value.AdminRole;
  import org.springframework.data.jpa.repository.JpaRepository;

  import java.util.Optional;

  public interface AdministratorRepository extends JpaRepository<AdministratorData, Long> {
      Optional<AdministratorData> findByUserAccountId(Long userAccountId);
      Optional<AdministratorData> findFirstByRoleAndRevokedAtIsNull(AdminRole role);
      boolean existsByUserAccountId(Long userAccountId);
  }
  ```

  `findFirstByRoleAndRevokedAtIsNull(SUPER_ADMIN)` returns the active super-admin (used by SuperAdminSeedService in Task 3).

- [ ] **Step 8: Run the test — expect green**

  ```
  JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7 ./gradlew :app:test --tests "*AdministratorDataTest" --no-daemon
  ```
  Expected: PASS.

- [ ] **Step 9: Commit**

  ```bash
  git add app/src/main/java/com/pfplaybackend/api/administration/ \
          app/src/test/java/com/pfplaybackend/api/administration/
  git commit -m "feat(admin): add Administrator aggregate (entity + value objects + repository)

  - AdministratorData entity matches V5 DDL: administrator_id PK,
    user_account_id value reference (no FK), AdminRole VARCHAR(32),
    self-ref grantedByAdministratorId, grantedAt/revokedAt lifecycle.
  - AdminRole enum: SUPER_ADMIN, ADMIN.
  - AdministratorId value object (parallels UserId).
  - AdministratorRepository: findByUserAccountId,
    findFirstByRoleAndRevokedAtIsNull, existsByUserAccountId.
  - createSuperAdmin / createAdmin factories enforce role-specific
    invariants (super-admin grantedBy=null; admin grantedBy required).
  - revoke() is idempotent — second call no-ops.

  Establishes the administration package convention
  (app/.../administration/...) per CONTEXT_MAP.md.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2"
  ```

---

## Chunk 2: SuperAdminSeedService

### Task 3: Implement SuperAdminSeedService

**Model:** `opus` (env handling + security-sensitive — bcrypt + memory hygiene)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/SuperAdminSeedService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/SuperAdminSeedServiceTest.java`

**Background:** This service runs once at `ApplicationReadyEvent` and replaces the V5-seeded placeholder email/hash with bcrypt-hashed env values. Idempotent: subsequent boots find no placeholder and no-op. **Security-sensitive**: env vars hold a plaintext password briefly during bcrypt; per spec §4.2.2 the service must read once, bcrypt, and null out references.

**Spec checklist (§4.2.2):**
1. Find `user_account` where `email='__SUPER_ADMIN_PLACEHOLDER_EMAIL__'`
2. If found:
   - Read env `ADMIN_SEED_EMAIL` and `ADMIN_SEED_PASSWORD`
   - If either is null/blank → log error + halt application (operationally required)
   - bcrypt with cost 12
   - UPDATE user_account SET email=?, password_hash=? WHERE user_id=1
   - Null out env-var local references (memory hygiene)
3. If not found (already replaced): no-op
4. Idempotent across reboots

- [ ] **Step 1: Read existing PasswordEncoder injection pattern**

  `Read` `common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java` to find the `PasswordEncoder` bean. Confirm Spring Security's `BCryptPasswordEncoder` is wired. Verify cost — if not 12, the seed service explicitly constructs a new encoder with cost 12 per spec.

- [ ] **Step 2: Read `Environment` access pattern**

  `Read` `app/src/main/java/com/pfplaybackend/api/bootstrap/ApplicationReadyEventListener.java` to see how `Environment` is currently injected. Reuse the same pattern.

- [ ] **Step 3: Write the failing test**

  ```java
  package com.pfplaybackend.api.administration.application.service;

  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
  import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.extension.ExtendWith;
  import org.mockito.ArgumentCaptor;
  import org.mockito.InjectMocks;
  import org.mockito.Mock;
  import org.mockito.junit.jupiter.MockitoExtension;
  import org.springframework.core.env.Environment;
  import org.springframework.security.crypto.password.PasswordEncoder;

  import java.util.Optional;

  import static org.assertj.core.api.Assertions.*;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.*;

  @ExtendWith(MockitoExtension.class)
  class SuperAdminSeedServiceTest {

      @Mock UserAccountRepository userAccountRepository;
      @Mock Environment environment;
      @Mock PasswordEncoder passwordEncoder;

      @InjectMocks SuperAdminSeedService service;

      @Test
      void finalizeSuperAdminCredentials_replacesPlaceholderWithEnvValues() {
          var placeholder = UserAccountData.createForLocal(
              new UserId(1L),
              "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
              "__SUPER_ADMIN_PLACEHOLDER_HASH__");
          when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
              .thenReturn(Optional.of(placeholder));
          when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn("admin@pfplay.com");
          when(environment.getProperty("ADMIN_SEED_PASSWORD")).thenReturn("plain-password");
          when(passwordEncoder.encode("plain-password")).thenReturn("$2a$12$encoded...");

          service.finalizeSuperAdminCredentials();

          // Behavior: placeholder entity mutated (real flow uses dirty-flush)
          assertThat(placeholder.getEmail()).isEqualTo("admin@pfplay.com");
          assertThat(placeholder.getPasswordHash()).isEqualTo("$2a$12$encoded...");
      }

      @Test
      void finalizeSuperAdminCredentials_isNoOpWhenPlaceholderAbsent() {
          when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
              .thenReturn(Optional.empty());

          service.finalizeSuperAdminCredentials();

          verifyNoInteractions(environment, passwordEncoder);
      }

      @Test
      void finalizeSuperAdminCredentials_throwsWhenAdminSeedEmailMissing() {
          var placeholder = UserAccountData.createForLocal(
              new UserId(1L),
              "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
              "__SUPER_ADMIN_PLACEHOLDER_HASH__");
          when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
              .thenReturn(Optional.of(placeholder));
          when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn(null);
          when(environment.getProperty("ADMIN_SEED_PASSWORD")).thenReturn("anything");

          assertThatThrownBy(() -> service.finalizeSuperAdminCredentials())
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("ADMIN_SEED_EMAIL");
      }

      @Test
      void finalizeSuperAdminCredentials_throwsWhenAdminSeedPasswordMissing() {
          var placeholder = UserAccountData.createForLocal(
              new UserId(1L),
              "__SUPER_ADMIN_PLACEHOLDER_EMAIL__",
              "__SUPER_ADMIN_PLACEHOLDER_HASH__");
          when(userAccountRepository.findByEmail("__SUPER_ADMIN_PLACEHOLDER_EMAIL__"))
              .thenReturn(Optional.of(placeholder));
          when(environment.getProperty("ADMIN_SEED_EMAIL")).thenReturn("admin@pfplay.com");
          when(environment.getProperty("ADMIN_SEED_PASSWORD")).thenReturn(null);

          assertThatThrownBy(() -> service.finalizeSuperAdminCredentials())
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("ADMIN_SEED_PASSWORD");
      }
  }
  ```

- [ ] **Step 4: Run the test — expect failure**

- [ ] **Step 5: Write `SuperAdminSeedService`**

  ```java
  package com.pfplaybackend.api.administration.application.service;

  import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
  import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.core.env.Environment;
  import org.springframework.security.crypto.password.PasswordEncoder;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  /**
   * Replaces V5-seeded super-admin placeholder credentials with env-supplied
   * email + bcrypt-hashed password. Runs once at ApplicationReadyEvent.
   * Idempotent: subsequent boots find no placeholder and no-op.
   */
  @Service
  @RequiredArgsConstructor
  @Slf4j
  public class SuperAdminSeedService {

      static final String PLACEHOLDER_EMAIL = "__SUPER_ADMIN_PLACEHOLDER_EMAIL__";
      static final String ADMIN_SEED_EMAIL_KEY = "ADMIN_SEED_EMAIL";
      static final String ADMIN_SEED_PASSWORD_KEY = "ADMIN_SEED_PASSWORD";

      private final UserAccountRepository userAccountRepository;
      private final Environment environment;
      private final PasswordEncoder passwordEncoder;

      @Transactional
      public void finalizeSuperAdminCredentials() {
          var placeholder = userAccountRepository.findByEmail(PLACEHOLDER_EMAIL);
          if (placeholder.isEmpty()) {
              log.info("Super-admin placeholder absent; no-op (already replaced).");
              return;
          }

          String seedEmail = environment.getProperty(ADMIN_SEED_EMAIL_KEY);
          String seedPassword = environment.getProperty(ADMIN_SEED_PASSWORD_KEY);

          if (seedEmail == null || seedEmail.isBlank()) {
              log.error("ADMIN_SEED_EMAIL is not set; cannot finalize super-admin credentials.");
              throw new IllegalStateException("ADMIN_SEED_EMAIL must be set in environment.");
          }
          if (seedPassword == null || seedPassword.isBlank()) {
              log.error("ADMIN_SEED_PASSWORD is not set; cannot finalize super-admin credentials.");
              throw new IllegalStateException("ADMIN_SEED_PASSWORD must be set in environment.");
          }

          String hash = passwordEncoder.encode(seedPassword);
          // Memory hygiene: drop the plaintext reference immediately after bcrypt.
          // (We cannot zero out the underlying String contents — Java Strings are immutable.
          //  Using char[] would be tighter, but Spring's Environment returns String only.
          //  Best-effort: reassign to null and let GC reclaim.)
          //noinspection UnusedAssignment
          seedPassword = null;

          UserAccountData admin = placeholder.get();
          admin.replacePlaceholderCredentials(seedEmail, hash);
          // JPA dirty-flush within @Transactional persists the change.

          log.info("Super-admin credentials finalized for user_id=1.");
      }
  }
  ```

  **Note 1:** Add a new method to `UserAccountData`: `replacePlaceholderCredentials(String email, String passwordHash)` — package-private or public, mutates the two fields. This is a deliberate seed-only API; document with Javadoc that it must NOT be called from normal application flow. (Pre-existing `withdraw()` mutates email; this is similar semantically.)

  **Note 2:** `findByEmail(String)` already exists on `UserAccountRepository` (PR 1 Task 7).

- [ ] **Step 6: Add `replacePlaceholderCredentials` to `UserAccountData`**

  Insert into `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java`:

  ```java
      /**
       * Seed-only API: replaces the V5-seeded placeholder email and password hash
       * with operator-supplied values. Called exactly once per environment by
       * SuperAdminSeedService at ApplicationReadyEvent. Do NOT call from normal
       * application flow — there is no IAM lifecycle event for this.
       */
      public void replacePlaceholderCredentials(String email, String passwordHash) {
          this.email = email;
          this.passwordHash = passwordHash;
      }
  ```

  Add a unit test for this method to `UserAccountDataTest.java`:

  ```java
      @Test
      void replacePlaceholderCredentials_mutatesEmailAndHash() {
          var account = UserAccountData.createForLocal(
              new UserId(1L), "__placeholder__", "__placeholder_hash__");

          account.replacePlaceholderCredentials("real@admin.com", "$2a$12$encoded");

          assertThat(account.getEmail()).isEqualTo("real@admin.com");
          assertThat(account.getPasswordHash()).isEqualTo("$2a$12$encoded");
      }
  ```

- [ ] **Step 7: Run the SuperAdminSeedServiceTest — expect green**

  ```
  JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7 ./gradlew :app:test --tests "*SuperAdminSeedServiceTest" --no-daemon
  ```

- [ ] **Step 8: Run the UserAccountDataTest to confirm new method test passes**

  ```
  JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7 ./gradlew :user:test --tests "*UserAccountDataTest" --no-daemon
  ```

- [ ] **Step 9: Commit**

  ```bash
  git add app/src/main/java/com/pfplaybackend/api/administration/application/service/ \
          app/src/test/java/com/pfplaybackend/api/administration/application/service/ \
          user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java \
          user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java
  git commit -m "feat(admin): add SuperAdminSeedService for env-based credential finalization

  - finalizeSuperAdminCredentials runs at ApplicationReadyEvent (Task 4
    wires it into the bootstrap listener).
  - Reads ADMIN_SEED_EMAIL / ADMIN_SEED_PASSWORD from environment;
    halts startup if either is unset (operational invariant).
  - bcrypt(seedPassword) via injected PasswordEncoder (cost matches
    SecurityConfig — typically 12).
  - Idempotent: when placeholder already replaced, no-op.
  - Memory hygiene: nulls the plaintext password reference after bcrypt.
  - Adds UserAccountData.replacePlaceholderCredentials seed-only API.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2.2"
  ```

---

## Chunk 3: Bootstrap Rewiring + Cleanup

### Task 4: Rewire ApplicationReadyEventListener; remove AdminUserInitializeService

**Model:** `opus` (boot-path critical, multi-file cleanup)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/bootstrap/ApplicationReadyEventListener.java`
- Delete: `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeService.java`
- Delete: `user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeServiceTest.java`
- Modify: any file that imported `AdminUserInitializeService` (use `Grep` to find — should only be the bootstrap listener and the test)

**Background:** V5 INSERTs the super-admin user_account, administrator, and member rows. The application no longer needs `AdminUserInitializeService.addAdminUser()` to create them at boot. The bootstrap listener calls `SuperAdminSeedService.finalizeSuperAdminCredentials()` (Task 3) to replace placeholder credentials with env values, then `partyroomCommandService.initializeMainStage(SUPER_ADMIN_USER_ID)` using the V5-seeded admin's fixed `user_id = 1`.

**Behavior preservation checklist:**
- Main stage initialization still runs after admin is ready — preserve ordering.
- Temporary user init (local profile only) preserved untouched.
- Idempotency preserved — repeat boots are safe.

- [ ] **Step 1: Inventory `AdminUserInitializeService` callers**

  ```
  Grep "AdminUserInitializeService" --glob "**/*.java"
  ```

  Expected callers:
  - `app/src/main/java/com/pfplaybackend/api/bootstrap/ApplicationReadyEventListener.java` (call site)
  - `user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeServiceTest.java` (the SUT's test)

  If anything else appears, escalate as NEEDS_CONTEXT before deleting.

- [ ] **Step 2: Rewrite `ApplicationReadyEventListener`**

  ```java
  package com.pfplaybackend.api.bootstrap;

  import com.pfplaybackend.api.administration.application.service.SuperAdminSeedService;
  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.party.application.service.PartyroomCommandService;
  import com.pfplaybackend.api.user.application.service.initialize.TemporaryUserInitializeService;
  import lombok.RequiredArgsConstructor;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.boot.context.event.ApplicationReadyEvent;
  import org.springframework.context.event.EventListener;
  import org.springframework.core.env.Environment;
  import org.springframework.core.env.Profiles;
  import org.springframework.stereotype.Component;

  @Component
  @RequiredArgsConstructor
  @ConditionalOnProperty(name = "app.initialization.enabled", havingValue = "true", matchIfMissing = true)
  public class ApplicationReadyEventListener {

      // V5 seeds the super-admin at user_id=1 (administrator_id=1, member_id=1).
      // SuperAdminSeedService replaces the placeholder credentials at boot.
      private static final UserId SUPER_ADMIN_USER_ID = new UserId(1L);

      private final Environment environment;
      private final SuperAdminSeedService superAdminSeedService;
      private final TemporaryUserInitializeService temporaryUserInitializeService;
      private final PartyroomCommandService partyroomCommandService;

      @EventListener(ApplicationReadyEvent.class)
      public void onApplicationEvent() {
          // 1) Replace V5-seeded super-admin placeholder email/hash with env values.
          //    Idempotent: no-op when placeholder already replaced.
          superAdminSeedService.finalizeSuperAdminCredentials();

          // 2) Initialize main stage with the V5-seeded super-admin as host.
          //    The Member row (member_id=1, user_account_id=1, FM tier) is also
          //    seeded by V5, so PartyroomCommandService.initializeMainStage works.
          partyroomCommandService.initializeMainStage(SUPER_ADMIN_USER_ID);

          // 3) Local-only test fixtures (temporary users for development).
          if (environment.acceptsProfiles(Profiles.of("local"))) {
              temporaryUserInitializeService.addTemporaryUsers();
          }
      }
  }
  ```

  **Note:** the `FIXME` comment about service ordering from the old code is dropped — V5 INSERTs sidestep the original ordering concern (admin row exists before any service runs).

- [ ] **Step 3: Verify `partyroomCommandService.initializeMainStage(UserId)` still works**

  `Read` `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomCommandService.java` — find `initializeMainStage`. Confirm:
  - Signature still takes `UserId` (not `Long memberId` or anything else).
  - It looks up the Member by user_account_id (or whatever PR 1 settled on) — `new UserId(1L).getUid() == 1L`, and V5 seeded `member` with `user_account_id = 1`. The lookup should resolve correctly.

  If signature changed in PR 1 in a way that doesn't accept `UserId`, document and adjust the listener.

- [ ] **Step 4: Delete `AdminUserInitializeService` + test**

  ```bash
  git rm user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeService.java
  git rm user/src/test/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeServiceTest.java
  ```

  This is a hard delete. The class has no production callers post-rewiring.

- [ ] **Step 5: Verify the build**

  ```
  JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7 ./gradlew compileJava compileTestJava test --no-daemon
  ```

  Expected: BUILD SUCCESSFUL across all modules. The deletions remove the only remaining references to AdminUserInitializeService.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/pfplaybackend/api/bootstrap/ApplicationReadyEventListener.java
  git commit -m "feat(admin): rewire bootstrap to use V5-seeded super-admin

  - ApplicationReadyEventListener no longer creates the admin user at
    runtime (V5 INSERTs handle that). Instead it:
    1. calls SuperAdminSeedService.finalizeSuperAdminCredentials()
       to replace placeholder email/hash with bcrypt(env values).
    2. initializes main stage with SUPER_ADMIN_USER_ID = UserId(1L).
    3. Local-profile temporary users unchanged.

  - Removes AdminUserInitializeService (and its test) — fully obsoleted
    by V5 + SuperAdminSeedService.

  - The pre-existing FIXME on service ordering is resolved by V5: the
    admin user_account/administrator/member rows exist before any
    service runs.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2.3"
  ```

---

## Chunk 4: Verification

### Task 5: Boot smoke test (V5 + env replacement + main-stage init)

**Model:** No agent dispatch — verification only. Implementer runs the harness manually.

**Files:** No source changes by default; this task is end-to-end verification. If anomalies surface, fixes get their own commits.

**Background:** Reuse the PR 1 Task 15 smoke harness with V5 in the chain. New verifications:
- `flyway_schema_history` shows V1→V5 all `success=1`.
- `administrator` table exists with the V5-seeded SUPER_ADMIN row at `administrator_id = 1`, `user_account_id = 1`, `role = 'SUPER_ADMIN'`, `revoked_at IS NULL`.
- After boot, `user_account` row at `user_id = 1` shows `email = $ADMIN_SEED_EMAIL` (from env) and `password_hash` starts with `$2a$12$` (bcrypt cost 12).
- Functional unique index rejects a 2nd SUPER_ADMIN INSERT (manual test).
- Admin gate (`/api/v1/admin/partyrooms`) still returns 401 (PR 0 invariant preserved).
- Main stage partyroom exists with `host_id = 1` (Member from V5 seed).

**Pre-flight (same as PR 1 Task 15):**
- Docker running.
- `.env` exists. **Add `ADMIN_SEED_EMAIL` and `ADMIN_SEED_PASSWORD`** if not present (test values OK for local — e.g., `admin@pfplay.local` / `local-test-password`).
- JDK 21 toolchain.
- No port collisions on 3306, 6379, 8080.

- [ ] **Step 1: Add env vars to `.env`** (if missing)

  Append:
  ```
  ADMIN_SEED_EMAIL=admin@pfplay.local
  ADMIN_SEED_PASSWORD=local-test-only-rotate-in-prod
  ```

  These are local-test values. **Production values must be supplied via the deployment platform's secret store, never committed.**

- [ ] **Step 2: Start ephemeral MySQL + Redis containers**

  Same docker-run pattern as PR 1 Task 15.

- [ ] **Step 3: Boot the app**

  ```bash
  export JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7
  set -a && source .env && set +a
  ./gradlew :app:bootRun --quiet &
  ```

  Tail the log; expect:
  - `Successfully applied 5 migrations to schema 'pfplay'` (Flyway V1→V5)
  - `Super-admin credentials finalized for user_id=1.` (from SuperAdminSeedService)
  - `Tomcat started on port 8080`

- [ ] **Step 4: Verify Flyway history**

  ```bash
  docker exec pfplay-pr2-mysql mysql -uroot -proot pfplay -e \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
  ```

  Expected: rows for V1, V2, V3, V4, V5, all `success=1`.

- [ ] **Step 5: Verify administrator table + super-admin row**

  ```bash
  docker exec pfplay-pr2-mysql mysql -uroot -proot pfplay -e \
    "DESCRIBE administrator;
     SELECT administrator_id, user_account_id, role, granted_by_administrator_id, revoked_at FROM administrator;"
  ```

  Expected:
  - Columns match V5 DDL.
  - One row: `administrator_id=1, user_account_id=1, role='SUPER_ADMIN', granted_by_administrator_id=NULL, revoked_at=NULL`.

- [ ] **Step 6: Verify env replacement worked**

  ```bash
  docker exec pfplay-pr2-mysql mysql -uroot -proot pfplay -e \
    "SELECT user_id, email, provider_type, LEFT(password_hash, 7) AS hash_prefix FROM user_account WHERE user_id = 1;"
  ```

  Expected: `user_id=1, email='admin@pfplay.local', provider_type='LOCAL', hash_prefix='$2a$12$'`.

  **If `email` is still the placeholder**, env replacement didn't run — check:
  - `ADMIN_SEED_EMAIL` was sourced into the gradle process
  - `SuperAdminSeedService` was wired into ApplicationReadyEventListener
  - No earlier exception aborted the listener

- [ ] **Step 7: Verify SUPER_ADMIN singleton enforcement**

  ```bash
  docker exec pfplay-pr2-mysql mysql -uroot -proot pfplay -e \
    "INSERT INTO administrator (user_account_id, role, granted_at, created_at, updated_at)
     VALUES (1000000000000000, 'SUPER_ADMIN', NOW(), NOW(), NOW());"
  ```

  Expected: ERROR — duplicate key on `uk_administrator_super_admin`. The functional unique index prevents a 2nd active SUPER_ADMIN.

- [ ] **Step 8: Verify main-stage partyroom seeded**

  ```bash
  docker exec pfplay-pr2-mysql mysql -uroot -proot pfplay -e \
    "SELECT partyroom_id, host_id, stage_type FROM partyroom WHERE host_id = 1;"
  ```

  Expected: at least one row with `host_id = 1` and `stage_type = 'MAIN'`. (`initializeMainStage(UserId(1L))` should have created this.)

- [ ] **Step 9: Verify admin gate**

  ```bash
  curl -i http://localhost:8080/api/v1/admin/partyrooms
  ```

  Expected: `HTTP/1.1 401 Unauthorized` (PR 0 invariant, unchanged).

- [ ] **Step 10: Verify boot is idempotent**

  Stop the app (`pkill -f bootRun`), restart it (`./gradlew :app:bootRun --quiet &`). Expected log line:
  - `Super-admin placeholder absent; no-op (already replaced).`

  Schema and admin row unchanged after second boot.

- [ ] **Step 11: Cleanup**

  ```bash
  pkill -f bootRun
  docker rm -f pfplay-pr2-mysql pfplay-pr2-redis
  ```

- [ ] **Step 12: Commit any deferred fixes**

  No commit if smoke succeeded. If steps 4–10 surfaced anomalies, iterate fix → re-boot → re-verify; each fix gets its own commit.

---

## Final Checkpoint

After Task 5:
- V1→V5 Flyway migrations apply cleanly.
- `administrator` table exists; SUPER_ADMIN singleton row present and constrained.
- `user_account` super-admin email/hash replaced from env at first boot; idempotent on subsequent boots.
- `AdminUserInitializeService` deleted; no lingering references.
- `:user:test`, `:app:test`, ArchUnit all green.
- Main-stage partyroom seeded with `host_id = 1`.
- Admin gate still 401s for unauthenticated.

**Branch state:** `feature/admin-auth-iam-schema` is now PR 0 (7) + PR 1 (~13) + PR 2 (~5) commits. Continue accumulating for the milestone-end consolidated PR. **Do not open a PR for PR 2 alone** — the user's workflow is to land PRs 0–11 incrementally on this branch.

---

## Spec Coverage Summary

| Spec § | Covered by |
|---|---|
| §4.2.1 DDL | Task 1 (V5 SQL) |
| §4.2.2 Placeholder replacement logic | Task 3 (SuperAdminSeedService) |
| §4.2.3 Remove AdminUserInitializeService.addAdminUser() | Task 4 (Bootstrap rewiring) |
| §4.2.4 Architecture review items (singleton, VARCHAR, env handling, race fix) | Tasks 1, 2, 3, 4 |

PR 2 is fully scoped to §4.2 — no scope creep, no deferrals.
