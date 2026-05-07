# PR 1: V4 IAM Refactor Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert UserAccount/Member/Guest from JPA `@Inheritance(JOINED)` to composition (independent entities + value-typed `userAccountId` reference), relocate `authorityTier`/`profileData` to `Member` (Party context), migrate `provider_type` storage from `tinyint(ORDINAL)` to `VARCHAR(16) STRING`, and prepare IAM-scoped lifecycle fields (`last_login_at`, `withdrawn_at`).

**Architecture:** Single Flyway `V4__refactor_user_account_to_iam.sql` (DROP + CREATE strategy — pre-launch, no real users). All entity/repository/application-layer changes ship in the same PR because the inheritance removal is a coordinated breaking change. Composition makes `UserAccount` the IAM aggregate root; `Member` and `Guest` are Party/User-Profile aggregates that hold `userAccountId` as a value reference (no FK across BC). `provider_type` becomes VARCHAR with `LOCAL` value added (admin local login + virtual users) and `ADMIN` value removed.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring Data JPA, Hibernate, MySQL 8.0, Flyway, Lombok, JUnit 5, Testcontainers (existing), Gradle Kotlin DSL multi-module (`common`, `user`, `app`, `playlist`, `realtime`).

**Spec source:** `docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1`. Read that section before starting any task in this plan — it is the source of truth for DDL, semantics, and breaking-change inventory.

**Compilation policy:** Tasks 4–7 (entity layer rewrite) intentionally leave the application-layer code uncompilable until Tasks 8–13 finish patching call-sites. **Do not attempt to "fix" call-sites inside the entity tasks.** Each chunk closes with a checkpoint that re-establishes a green build. Chunk 4 is the final integration verification.

**Verified codebase facts** (read once at plan-write time, applied throughout):
- `BaseEntity` lives at `common/.../entity/BaseEntity.java`. It exposes `protected void registerEvent(DomainEvent e)` and `public List<DomainEvent> pollDomainEvents()` (drain-on-read). Audit columns `createdAt` / `updatedAt` use `columnDefinition` only — Hibernate maps them via field-name convention (`created_at`, `updated_at`), which matches V4 DDL exactly.
- `DomainEvent` lives at `common/.../domain/event/DomainEvent.java` and is a **class** (not interface). New events extend it and override `getAggregateId()`. Reference: `MemberRegisteredEvent`, `UserProfileChangedEvent` (in `user/.../domain/event/`).
- `ActivityData` is its own JPA entity (`user/.../domain/entity/data/ActivityData.java`) with `@Embedded UserId userId` mapped to `@Column(name = "user_id")` and a public `addScore(int delta)` method. **There is currently NO standalone `ActivityRepository`** — Task 11 creates it. The `@OneToMany activityDataMap` on the current `MemberData` is being **dropped** in this PR; UserActivityCommandService and other consumers migrate to the new repository in Chunk 3.
- `MemberRepositoryImpl` (QueryDSL custom) currently exposes `findByUserId(UserId)` via `qMemberData.userId.eq(userId)`. After the refactor, `MemberData` has no `userId` field, so the QueryDSL predicate must be rewritten against `userAccountId` (Long) — Task 7 covers this. `GuestRepositoryImpl` (if it exists) needs the same treatment per Task 12.
- `UserDomainEventRelay` (a `@TransactionalEventListener`) currently calls `memberRepository.findByUserId(event.getUserId())`. After Task 7 renames that method, this call-site is patched in Task 11.
- The legacy `UserId` VO value equals `member.userAccountId` by construction: every UserAccount factory uses a `UserId` (auto-generated or fixed), and `Member.createForUserAccount(Long)` is always called with `userAccount.getUserId().getUid()`. JWT subject and event payloads continue to carry the same Long value (string-cast for JWT). Existing JWT consumers in `common/.../jwt/*` need no change.
- `UserAccountRepository.findByUserId(UserId)` is **preserved** in Task 7 — UserAccount's PK remains the legacy `UserId` VO, so this method stays. Member/Guest's `findByUserId(UserId)` is what gets renamed to `findByUserAccountId(Long)`.

**Branching:** Work continues on the existing `feature/admin-auth-iam-schema` branch. Each task ends in its own commit; no rebase/squash until the branch ships. This branch already carries PR 0 (admin endpoint security gate, 7 commits ahead of `develop`).

---

## File Inventory

### Files Created
- `app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql` — Flyway migration (DROP + CREATE for `user_account`, `member`, `guest`)
- `user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java` — Domain event published by `UserAccount.withdraw()` (consumed in later PRs by Administration / User Profile listeners; the event contract lives in this PR for forward-compat)
- (Possibly new) `user/src/main/java/com/pfplaybackend/api/user/domain/value/UserAccountId.java` — value object wrapping `Long` for cross-BC identity. **Decision in Task 4** based on whether the reviewer wants to defer this until PR 2 (Administration also references it). Default: **defer to PR 2**, use raw `Long` userAccountId for now to minimize churn.
- New tests for `UserAccount`, `Member`, `Guest` composition behaviour

### Files Modified (≈40+ files; the major ones)
- `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java` — add `LOCAL`, remove `ADMIN`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java` — remove `@Inheritance`, become standalone with `email`/`providerType`/`passwordHash`/`lastLoginAt`/`withdrawnAt`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java` — extend `BaseEntity`, add `memberId` PK + `userAccountId` ref, absorb `authorityTier`/`profileData`/`isProfileUpdated`, drop `email`/`providerType`
- `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/GuestData.java` — extend `BaseEntity`, add `guestId` PK + `userAccountId` ref, absorb `profileData`/`isProfileUpdated`/`authorityTier`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java` — add `findByEmailAndProviderType`, `existsByEmail`, `findByEmail`
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java` + `MemberRepositoryCustom*` — replace `findByEmail` / `countByProviderType` with `findByUserAccountId` + UserAccount-join queries
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/GuestRepository.java` + Custom — `findByUserAccountId`
- `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java` — OAuth flow now creates `UserAccount` + `Member` in two stages
- `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeService.java` — UserAccount + Member two-stage; provider stays `GOOGLE` (PR 2 will replace this hook with V5 INSERT)
- `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeService.java` — UserAccount + Member/Guest two-stage
- `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java` — JWT claim sourcing: email from `UserAccount`, authorityTier from `Member`
- `common/src/main/java/com/pfplaybackend/api/common/config/security/jwt/JwtService.java` — claim resolution change is consumer-side only (no signature change, just where the data comes from in callers)
- `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminUserService.java` — `createVirtualMember` uses `ProviderType.LOCAL`; UserAccount + Member two-stage
- `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminDemoService.java` — `countMembersByProviderType(ADMIN)` → `(LOCAL)`
- `app/src/main/java/com/pfplaybackend/api/admin/application/port/out/AdminMemberPort.java` (+ Adapter) — `countMembersByProviderType(LOCAL)`; query joins UserAccount
- `user/src/main/java/com/pfplaybackend/api/user/adapter/in/web/payload/response/QueryMyInfoResponse.java` — `.getEmail()` resolves through UserAccount
- `app/src/main/java/com/pfplaybackend/api/admin/adapter/in/web/AdminUserController.java:139–145` — profile data still on Member (no source change), email from UserAccount
- `user/src/main/java/com/pfplaybackend/api/user/adapter/out/event/UserDomainEventRelay.java` — adjust if it touches UserAccount-vs-Member fields
- `app/src/test/...` — update existing tests that broke (compile + assertion errors)

A complete searchable list will be regenerated at the end of Chunk 3 via `./gradlew compileJava` failure dump; no need to enumerate all 40+ files upfront.

---

## Test Strategy

| Layer | Test type | Notes |
|---|---|---|
| Entity (UserAccount/Member/Guest) | Unit (POJO) | Factory methods, `withdraw()` event publication, invariant guards. JUnit 5, no Spring context. |
| Repository | `@DataJpaTest` slice with Testcontainers MySQL | New `findByUserAccountId`, `findByEmail`, etc. Reuse existing Testcontainers MySQL config (see `pfplay-platform/app/src/test/.../config/TestContainerConfig.java`). |
| Application service | Mockito unit + (where existing) `@SpringBootTest` integration | Update existing `MemberSignServiceTest`, `AuthServiceTest`, etc. to match new two-stage creation. |
| Migration | Boot smoke test (Chunk 4 final task) | Boot the app against a fresh schema → Flyway runs V1→V4 → app starts cleanly → admin endpoint still 401s. |
| ArchUnit | Run existing `HexagonalArchitectureTest` | Should continue to pass — composition doesn't introduce new cross-module imports. |

---

## Chunk 1: Pre-refactor Preparations

These tasks add new symbols (`LOCAL`, `UserAccountWithdrawnEvent`) without touching the inheritance graph. The codebase stays compilable and all existing tests stay green.

### Task 1: Add ProviderType.LOCAL value (keep ADMIN for now)

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java`

**Background:** `ProviderType` currently holds `GOOGLE`, `TWITTER`, `ADMIN`. The spec (§4.1.1 (6)) replaces `ADMIN` with `LOCAL` (admin local login + virtual users use the same value). To keep the codebase compiling while we migrate call-sites, we add `LOCAL` first and remove `ADMIN` in **Chunk 4 Task 13** after every call-site has been migrated. No `@Enumerated(STRING)` annotation change here — that change happens on the new `UserAccountData` field in Task 4 (the JPA entity field carries the annotation, not the enum itself).

- [ ] **Step 1: Read the current enum**

  Run: confirm current shape via `Read` tool on the file. Expect 3 values: `GOOGLE, TWITTER, ADMIN`.

- [ ] **Step 2: Add LOCAL value at the end (alongside ADMIN)**

  ```java
  package com.pfplaybackend.api.common.config.security.enums;

  public enum ProviderType {
      GOOGLE,
      TWITTER,
      ADMIN, // DEPRECATED — to be removed in this PR (Task 10) after call-site migration
      LOCAL  // Admin local login + virtual users
  }
  ```

  **Why ADMIN stays temporarily:** existing code (`AdminUserService.createVirtualMember`, `AdminDemoService.countMembersByProviderType(ADMIN)`) compiles against `ADMIN`. We migrate them to `LOCAL` in Task 8, then drop `ADMIN` in Task 10.

  **Why ordinal placement matters:** With current `@Enumerated(ORDINAL)` storage, ordinals are persisted as `tinyint`. **DO NOT** insert `LOCAL` between existing values — appending preserves `GOOGLE=0`, `TWITTER=1`, `ADMIN=2` so any in-flight pre-V4 data reads consistently. (V4 wipes the tables anyway, but we ship in a non-atomic order — ProviderType change ships ahead of V4 within the same PR but compiled before migration runs locally.)

- [ ] **Step 3: Build the project**

  Run: `./gradlew compileJava --no-daemon`
  Expected: BUILD SUCCESSFUL (enum addition is non-breaking; `LOCAL` has no consumers yet).

- [ ] **Step 4: Run existing test suite**

  Run: `./gradlew test --no-daemon`
  Expected: all existing tests pass (no behaviour changes).

- [ ] **Step 5: Commit**

  ```bash
  git add common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java
  git commit -m "feat(iam): add ProviderType.LOCAL value for admin local login

  - LOCAL coexists with ADMIN during PR 1; ADMIN is removed in Task 10
    after AdminUserService and AdminDemoService migrate to LOCAL.
  - Ordinal placement preserves existing tinyint mappings (GOOGLE=0,
    TWITTER=1, ADMIN=2, LOCAL=3) for safety; V4 migration switches
    storage to VARCHAR(16) anyway.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.1 (6)"
  ```

---

### Task 2: Create UserAccountWithdrawnEvent domain event

**Files:**
- Create: `user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java` (basic record-shape test)

**Background:** The spec (§4.1.3) requires `UserAccount.withdraw()` to publish a `UserAccountWithdrawnEvent` event. PR 2 wires up the listener (Administration writes audit row); for PR 1 we ship the event class so the publication contract exists. Per verified codebase facts, `DomainEvent` is a **class** (not interface) — match the shape of `MemberRegisteredEvent` (extends `DomainEvent`, `@Getter`, overrides `getAggregateId()`).

- [ ] **Step 1: Confirm the established event shape**

  `Read` `user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberRegisteredEvent.java` and `common/src/main/java/com/pfplaybackend/api/common/domain/event/DomainEvent.java`. Confirm: events extend the abstract class (or its concrete equivalent), expose getters via Lombok, and override `getAggregateId()` returning a String.

- [ ] **Step 2: Write the failing test**

  ```java
  package com.pfplaybackend.api.user.domain.event;

  import org.junit.jupiter.api.Test;
  import static org.assertj.core.api.Assertions.assertThat;

  class UserAccountWithdrawnEventTest {

      @Test
      void getters_exposeFields() {
          var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local");

          assertThat(event.getUserAccountId()).isEqualTo(42L);
          assertThat(event.getAnonymizedEmail()).isEqualTo("withdrawn-42@withdrawn.local");
      }

      @Test
      void getAggregateId_returnsUserAccountIdAsString() {
          var event = new UserAccountWithdrawnEvent(42L, "withdrawn-42@withdrawn.local");

          assertThat(event.getAggregateId()).isEqualTo("42");
      }
  }
  ```

- [ ] **Step 3: Run the test and confirm it fails**

  Run: `./gradlew :user:test --tests "*UserAccountWithdrawnEventTest" --no-daemon`
  Expected: COMPILATION FAILURE (`UserAccountWithdrawnEvent` class doesn't exist).

- [ ] **Step 4: Write the event class extending DomainEvent**

  ```java
  package com.pfplaybackend.api.user.domain.event;

  import com.pfplaybackend.api.common.domain.event.DomainEvent;
  import lombok.Getter;

  @Getter
  public class UserAccountWithdrawnEvent extends DomainEvent {
      private final Long userAccountId;
      private final String anonymizedEmail; // post-anonymization placeholder

      public UserAccountWithdrawnEvent(Long userAccountId, String anonymizedEmail) {
          this.userAccountId = userAccountId;
          this.anonymizedEmail = anonymizedEmail;
      }

      @Override
      public String getAggregateId() {
          return userAccountId.toString();
      }
  }
  ```

  **Note:** If `DomainEvent` carries a `LocalDateTime occurredAt` or similar field auto-stamped in its constructor (which the existing `MemberRegisteredEvent` shape suggests), the constructor signature matches the parent's expectation. Verify in Step 1 — if `DomainEvent` requires a no-arg super-constructor only, this works as-is; if it requires arguments, adjust the constructor accordingly.

- [ ] **Step 5: Run the test**

  Run: `./gradlew :user:test --tests "*UserAccountWithdrawnEventTest" --no-daemon`
  Expected: PASS.

- [ ] **Step 6: Run the full module test suite to confirm no regression**

  Run: `./gradlew :user:test --no-daemon`
  Expected: PASS (event class is unused by anything yet).

- [ ] **Step 7: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEvent.java \
          user/src/test/java/com/pfplaybackend/api/user/domain/event/UserAccountWithdrawnEventTest.java
  git commit -m "feat(iam): add UserAccountWithdrawnEvent domain event

  Event published by UserAccount.withdraw() (wired in Task 4). Listener
  on Administration side ships in PR 2 with the audit-log subsystem.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.3"
  ```

---

## Chunk 2: V4 Migration + Entity Refactor

This chunk performs the inheritance-to-composition surgery. Compilation breaks across the application layer and stays broken until Chunk 3 patches call-sites. **Do not attempt to fix unrelated `compileJava` errors during these tasks** — they will be repaired in order in Chunk 3.

### Task 3: Write V4 Flyway migration SQL

**Files:**
- Create: `app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql`

**Background:** Pre-launch DROP + CREATE is acceptable per spec §4.1.5. The migration drops `member`, `guest`, `user_account` (in that order because of FK chain in V1) and recreates them per spec §4.1.2. **Do not run Flyway against any environment yet** — running this without the entity layer being in a matching shape (Tasks 4–5) will boot-fail JPA validation. Verification happens in Chunk 4.

- [ ] **Step 1: Verify the V1 FK shape one more time before authoring**

  Use `Grep` on `app/src/main/resources/db/migration/V1__init_schema.sql` for:
  - `references user_account` — confirm member/guest reference user_account
  - `unique_user_email` — confirm member.email UNIQUE constraint name
  - `is_profile_updated`, `authority_tier`, `profile_id` — confirm all live on `user_account` (parent table) in V1

- [ ] **Step 2: Write the migration file**

  Use the DDL below — it matches spec §4.1.2 functionally (same columns, types, constraints, indexes); the spec's inline Korean comments are dropped here for the production migration but no schema element is omitted. Critical points:
  - `provider_type VARCHAR(16) NOT NULL` (not `tinyint`)
  - `password_hash VARCHAR(255) NULL` (LOCAL-only; nullable)
  - `last_login_at`, `withdrawn_at` DATETIME NULL
  - `member.member_id BIGINT AUTO_INCREMENT PK`, `user_account_id BIGINT NOT NULL UNIQUE`
  - `member.profile_id` keeps FK to `user_profile(id)` (same Party context — FK allowed)
  - `guest.guest_id BIGINT AUTO_INCREMENT PK`, `user_account_id BIGINT NOT NULL UNIQUE`
  - `SET FOREIGN_KEY_CHECKS = 0;` wrap around the DROPs

  ```sql
  -- V4__refactor_user_account_to_iam.sql
  -- Pre-launch DROP + CREATE: inheritance(JOINED) → composition.
  -- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.2

  SET FOREIGN_KEY_CHECKS = 0;

  DROP TABLE IF EXISTS member;
  DROP TABLE IF EXISTS guest;
  DROP TABLE IF EXISTS user_account;

  SET FOREIGN_KEY_CHECKS = 1;

  CREATE TABLE user_account (
      user_id         BIGINT       NOT NULL,
      email           VARCHAR(255) NOT NULL,
      provider_type   VARCHAR(16)  NOT NULL,
      password_hash   VARCHAR(255) NULL,
      last_login_at   DATETIME     NULL,
      withdrawn_at    DATETIME     NULL,
      created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
      updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY (user_id),
      UNIQUE KEY uk_user_account_email (email)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE member (
      member_id            BIGINT          NOT NULL AUTO_INCREMENT,
      user_account_id      BIGINT          NOT NULL,
      authority_tier       ENUM('FM','AM','GT') NOT NULL,
      profile_id           BIGINT UNSIGNED NULL,
      is_profile_updated   BIT             NOT NULL DEFAULT 0,
      created_at           DATETIME        DEFAULT CURRENT_TIMESTAMP,
      updated_at           DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY (member_id),
      UNIQUE KEY uk_member_user_account (user_account_id),
      CONSTRAINT fk_member_profile FOREIGN KEY (profile_id) REFERENCES user_profile(id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  CREATE TABLE guest (
      guest_id         BIGINT          NOT NULL AUTO_INCREMENT,
      user_account_id  BIGINT          NOT NULL,
      agent            VARCHAR(255)    NULL,
      created_at       DATETIME        DEFAULT CURRENT_TIMESTAMP,
      updated_at       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      PRIMARY KEY (guest_id),
      UNIQUE KEY uk_guest_user_account (user_account_id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  ```

- [ ] **Step 3: Lint check (visual)**

  Re-read the file. Confirm no trailing parens, no missing commas. Flyway will reject malformed SQL only at runtime.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql
  git commit -m "feat(iam): add V4 Flyway migration for IAM composition refactor

  DROP + CREATE strategy (pre-launch). Recreates user_account as
  standalone IAM aggregate; member/guest become Party-context aggregates
  holding user_account_id as a value reference (no cross-BC FK).

  - provider_type: tinyint(ORDINAL) → VARCHAR(16)
  - last_login_at, withdrawn_at: new IAM lifecycle fields
  - member.member_id / guest.guest_id: new AUTO_INCREMENT PKs

  Migration not yet runnable end-to-end — entity refactor follows in
  Tasks 4–5. Boot smoke test runs in Chunk 4.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.2"
  ```

---

### Task 4: Refactor UserAccountData to standalone IAM aggregate

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java` (new)

**Background:** Strip `@Inheritance(JOINED)` and `@DiscriminatorColumn`. Drop `authorityTier`, `profileData`, `isProfileUpdated`, `getProfileSummary()`, `buildProfileSummary()`, `isGuest()` (these move to Member/Guest). Add `email`, `providerType` (`@Enumerated(STRING)`), `passwordHash`, `lastLoginAt`, `withdrawnAt`. Add `withdraw()` method that publishes `UserAccountWithdrawnEvent`.

**Identity decision:** The current `userId` field is `@EmbeddedId UserId` (a value object wrapping a Long). Keep this — it's already in `common` and used across modules. The new column name `user_id BIGINT` matches V4 DDL exactly.

**Domain event publication strategy:** Per verified codebase facts in the plan header, `BaseEntity` already provides `protected void registerEvent(DomainEvent e)` and `public List<DomainEvent> pollDomainEvents()`. `withdraw()` calls `registerEvent(new UserAccountWithdrawnEvent(...))`. The application service that invokes `withdraw()` is responsible for draining `pollDomainEvents()` and forwarding to `ApplicationEventPublisher` after the JPA save (the `UserDomainEventRelay` pattern already established in this module). Wiring of that drain happens in Chunk 3 inside whichever application service implements account withdrawal — for PR 1 we ship the entity-side contract only.

- [ ] **Step 1: Confirm the event/audit infrastructure shape**

  `Read` these three files (already verified in plan header — re-confirm at task time so the implementer is grounded):
  - `common/src/main/java/com/pfplaybackend/api/common/entity/BaseEntity.java` — confirm `registerEvent(DomainEvent)` and `pollDomainEvents()` exist; confirm `createdAt`/`updatedAt` columns rely on `columnDefinition` only (no explicit `name=` override) so they map to `created_at`/`updated_at` per Hibernate naming convention.
  - `common/src/main/java/com/pfplaybackend/api/common/domain/event/DomainEvent.java` — confirm it is a class (extends-pattern), what its no-arg constructor looks like, and whether it auto-stamps an `occurredAt` field.
  - `user/src/main/java/com/pfplaybackend/api/user/domain/event/MemberRegisteredEvent.java` — reference shape for the new event (already created in Task 2).

  If any assumption above is contradicted (e.g., `BaseEntity` does not actually expose `registerEvent`), STOP and escalate as BLOCKED — do not invent a parallel mechanism.

- [ ] **Step 2: Write the failing test FIRST**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.config.security.enums.ProviderType;
  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class UserAccountDataTest {

      @Test
      void create_setsRequiredFields() {
          var account = UserAccountData.createForSocial(
              new UserId(123L), "alice@gmail.com", ProviderType.GOOGLE);

          assertThat(account.getUserId().getUid()).isEqualTo(123L);
          assertThat(account.getEmail()).isEqualTo("alice@gmail.com");
          assertThat(account.getProviderType()).isEqualTo(ProviderType.GOOGLE);
          assertThat(account.getPasswordHash()).isNull();
          assertThat(account.getLastLoginAt()).isNull();
          assertThat(account.getWithdrawnAt()).isNull();
      }

      @Test
      void createForLocal_setsPasswordHash() {
          var account = UserAccountData.createForLocal(
              new UserId(1L), "admin@pfplay.local", "$2a$12$bcrypted...");

          assertThat(account.getProviderType()).isEqualTo(ProviderType.LOCAL);
          assertThat(account.getPasswordHash()).isEqualTo("$2a$12$bcrypted...");
      }

      @Test
      void withdraw_setsWithdrawnAtAndAnonymizesEmail() {
          var account = UserAccountData.createForSocial(
              new UserId(1L), "alice@gmail.com", ProviderType.GOOGLE);

          account.withdraw();

          assertThat(account.getWithdrawnAt()).isNotNull();
          assertThat(account.getEmail()).startsWith("withdrawn-").endsWith("@withdrawn.local");
      }

      @Test
      void withdraw_registersUserAccountWithdrawnEvent() {
          var account = UserAccountData.createForSocial(
              new UserId(7L), "alice@gmail.com", ProviderType.GOOGLE);

          account.withdraw();

          var events = account.pollDomainEvents();
          assertThat(events).hasSize(1);
          assertThat(events.get(0)).isInstanceOf(UserAccountWithdrawnEvent.class);
          var withdrawn = (UserAccountWithdrawnEvent) events.get(0);
          assertThat(withdrawn.getUserAccountId()).isEqualTo(7L);
          assertThat(withdrawn.getAnonymizedEmail()).startsWith("withdrawn-7@");
      }

      @Test
      void recordLogin_updatesLastLoginAt() {
          var account = UserAccountData.createForSocial(
              new UserId(1L), "alice@gmail.com", ProviderType.GOOGLE);
          assertThat(account.getLastLoginAt()).isNull();

          account.recordLogin();

          assertThat(account.getLastLoginAt()).isNotNull();
      }
  }
  ```

- [ ] **Step 3: Verify test fails**

  Run: `./gradlew :user:test --tests "*UserAccountDataTest" --no-daemon`
  Expected: COMPILATION FAILURE (factories don't exist yet) — that's the red.

- [ ] **Step 4: Rewrite UserAccountData to standalone**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.config.security.enums.ProviderType;
  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.common.entity.BaseEntity;
  import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Builder;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import org.hibernate.annotations.DynamicInsert;
  import org.hibernate.annotations.DynamicUpdate;

  import java.time.LocalDateTime;

  @Entity
  @Table(name = "user_account")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  @DynamicInsert
  @DynamicUpdate
  public class UserAccountData extends BaseEntity {

      @EmbeddedId
      @AttributeOverride(name = "uid", column = @Column(name = "user_id"))
      private UserId userId;

      @Column(name = "email", nullable = false, length = 255)
      private String email;

      @Column(name = "provider_type", nullable = false, length = 16)
      @Enumerated(EnumType.STRING)
      private ProviderType providerType;

      @Column(name = "password_hash", length = 255)
      private String passwordHash;

      @Column(name = "last_login_at")
      private LocalDateTime lastLoginAt;

      @Column(name = "withdrawn_at")
      private LocalDateTime withdrawnAt;

      @Builder(access = AccessLevel.PRIVATE)
      private UserAccountData(UserId userId, String email, ProviderType providerType,
                              String passwordHash, LocalDateTime lastLoginAt, LocalDateTime withdrawnAt) {
          this.userId = userId;
          this.email = email;
          this.providerType = providerType;
          this.passwordHash = passwordHash;
          this.lastLoginAt = lastLoginAt;
          this.withdrawnAt = withdrawnAt;
      }

      public static UserAccountData createForSocial(UserId userId, String email, ProviderType providerType) {
          if (providerType == ProviderType.LOCAL) {
              throw new IllegalArgumentException("Use createForLocal for LOCAL provider");
          }
          return UserAccountData.builder()
              .userId(userId)
              .email(email)
              .providerType(providerType)
              .build();
      }

      public static UserAccountData createForLocal(UserId userId, String email, String passwordHash) {
          return UserAccountData.builder()
              .userId(userId)
              .email(email)
              .providerType(ProviderType.LOCAL)
              .passwordHash(passwordHash)
              .build();
      }

      public void recordLogin() {
          this.lastLoginAt = LocalDateTime.now();
      }

      public void withdraw() {
          this.withdrawnAt = LocalDateTime.now();
          this.email = "withdrawn-" + this.userId.getUid() + "@withdrawn.local";
          registerEvent(new UserAccountWithdrawnEvent(this.userId.getUid(), this.email));
      }

      public boolean isWithdrawn() {
          return withdrawnAt != null;
      }
  }
  ```

  **Note on `registerEvent`:** This is the inherited `BaseEntity.registerEvent(DomainEvent)` method (verified in Step 1). The application service that calls `withdraw()` is responsible for draining `pollDomainEvents()` after persistence and forwarding to `ApplicationEventPublisher`. PR 1 does not include the drain wiring — the existing `UserDomainEventRelay` pattern can be extended in PR 2 when the listener side ships. As long as the entity-side contract is correct, the event class is reachable from any future drain mechanism.

  **Key removals from old version (move to Member/Guest in Tasks 6–7):**
  - `authorityTier` field
  - `profileData` field, `@OneToOne` mapping
  - `isProfileUpdated` field
  - `isGuest()` abstract method
  - `getProfileSummary()` / `buildProfileSummary()`
  - `getEmail()` returning null (now a real field)

- [ ] **Step 5: Run the UserAccountData test**

  Run: `./gradlew :user:test --tests "*UserAccountDataTest" --no-daemon`
  Expected: PASS (the entity compiles in isolation; downstream broken code is not on this test's classpath).

  **If the broader `:user:compileJava` fails** because `MemberData`/`GuestData` still extend the old shape — that's expected. Tasks 6–7 fix this. The unit test should still pass because it only loads the single class.

- [ ] **Step 6: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java \
          user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountDataTest.java
  git commit -m "refactor(iam): UserAccountData becomes standalone IAM aggregate

  - Remove @Inheritance(JOINED) + @DiscriminatorColumn
  - Move email, providerType from MemberData (will be undone there in
    Task 6); add passwordHash, lastLoginAt, withdrawnAt
  - Add createForSocial / createForLocal factories
  - Add withdraw() and recordLogin() lifecycle methods
  - Drop authorityTier/profileData/isProfileUpdated (move to Member/Guest
    in Tasks 6–7)

  Compilation across user module is intentionally broken until Tasks 6–9
  patch call-sites and child entities.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.3"
  ```

---

### Task 5: Refactor MemberData to composition

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/MemberDataTest.java` (new or updated)

**Background:** `MemberData` becomes a Party-context aggregate. PK is `member_id` (AUTO_INCREMENT `Long`). It holds `userAccountId` as a `Long` value (no FK at DB level — `uk_member_user_account` UNIQUE provides 1:1 enforcement). Fields `email` and `providerType` are removed (lookups now go through `UserAccountRepository`). Fields `authorityTier`, `profileData`, `isProfileUpdated` move IN from the old parent.

**`@AggregateRoot` annotation:** Keep it — Member is the User Profile / Party aggregate root.

**Factory contract:** Old factories took `(email, providerType)`. New factories take `(userAccountId)`. Email and provider live on UserAccount only.

**`activityDataMap` mapping decision (resolved at plan-write time):** The current `@OneToMany activityDataMap` on `MemberData` joins `user_activity.user_id` (legacy `UserId` VO column). After V4, `MemberData` no longer owns a `UserId` field — it has `userAccountId` (Long) and `memberId` (Long PK). Three options were considered:

  - **(rejected) Add `referencedColumnName = "user_account_id"` to keep the JOIN.** Hibernate accepts this only if the referenced column is `UNIQUE` at the JPA layer; the spec adds a DB UNIQUE constraint but `@Column(name="user_account_id")` does not declare `unique = true` at the JPA layer. Adding it works but couples the JPA mapping to the DDL constraint name fragility.
  - **(rejected) Keep a parallel `UserId userId` field on Member that mirrors `userAccountId`.** Two fields representing the same value invite drift bugs — exactly the kind of footgun this refactor is supposed to remove.
  - **(accepted) Drop the `@OneToMany` mapping from `MemberData` entirely.** `ActivityData` is its own aggregate. It is already accessed independently by `UserActivityCommandService` (verified by `Grep`). `MemberData.getProfileSummary()` is refactored to **accept** an `activitySummaries` argument from the application service rather than walking a JPA collection. The application service queries `ActivityData` via `activityRepository.findByUserAccountId(...)` (a small repository addition deferred to Chunk 3 alongside the call-site cascade).

  This sharpens the aggregate boundary: `Member` no longer owns the activity collection; `ActivityData` is referenced by `userAccountId` value (continuing the cross-aggregate value-reference convention used everywhere else in the refactor). Two methods are removed from `MemberData` as a consequence: `initializeActivityMap(Map)` and `updateDjScore(int)`. The init service and DJ score increment paths are patched in Chunk 3 (`UserActivityCommandService.addScore(userAccountId, delta)` already exists in spirit — confirm at task time).

  **Required Chunk 3 follow-ups (so the implementer remembers):**
  - `ActivityRepository` (or whatever the existing repository is named) gains `findByUserAccountId(Long)` if not already present — `Grep` for current methods.
  - `UserActivityCommandService.updateDjScore(...)` operates on `ActivityData` directly; remove the `member.updateDjScore(delta)` indirection.
  - Anywhere `member.getActivityDataMap()` was called, replace with an explicit query.
  - `MemberData.initializeActivityMap(...)` callers (init services) instead persist `ActivityData` rows directly via `activityRepository.saveAll(...)`.

- [ ] **Step 1: Update or write the failing test**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.enums.AuthorityTier;
  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class MemberDataTest {

      @Test
      void createForUserAccount_defaultsToAmTierAndUnupdatedProfile() {
          var member = MemberData.createForUserAccount(123L);

          assertThat(member.getUserAccountId()).isEqualTo(123L);
          assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.AM);
          assertThat(member.isProfileUpdated()).isFalse();
          assertThat(member.getMemberId()).isNull(); // assigned on persist
      }

      @Test
      void initializeProfile_setsProfileDataAndKeepsIsProfileUpdatedFalse() {
          var member = MemberData.createForUserAccount(1L);
          var profile = ProfileData.builder().build(); // assume builder exists

          member.initializeProfile(profile);

          assertThat(member.getProfileData()).isSameAs(profile);
          assertThat(member.isProfileUpdated()).isFalse();
      }

      @Test
      void updateProfileBio_marksProfileUpdated() {
          var member = MemberData.createForUserAccount(1L);
          member.initializeProfile(ProfileData.builder().build());

          member.updateProfileBio("Alice", "Hello");

          assertThat(member.isProfileUpdated()).isTrue();
      }

      @Test
      void updateWalletAddress_promotesAuthorityTierToFm() {
          var member = MemberData.createForUserAccount(1L);
          member.initializeProfile(ProfileData.builder().build());
          assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.AM);

          // member.updateWalletAddress(new WalletAddress("0x..."));
          // assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.FM);
          // ^ uncomment if WalletAddress VO is straightforward; otherwise leave
          // the test to the existing integration test that exercises this path.
      }
  }
  ```

  Adjust the test to whatever `ProfileData` / `WalletAddress` constructor shape exists. The intent is to lock in: (a) factory accepts `userAccountId`, (b) authority/profile fields live on Member, (c) wallet update promotes tier.

- [ ] **Step 2: Run the failing test**

  Run: `./gradlew :user:test --tests "*MemberDataTest" --no-daemon`
  Expected: COMPILATION FAILURE (new factory doesn't exist).

- [ ] **Step 3: Rewrite MemberData**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
  import com.pfplaybackend.api.common.entity.BaseEntity;
  import com.pfplaybackend.api.common.enums.AuthorityTier;
  import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
  import com.pfplaybackend.api.user.domain.value.*;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Builder;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import org.hibernate.annotations.DynamicInsert;
  import org.hibernate.annotations.DynamicUpdate;

  import java.util.List;

  @AggregateRoot
  @Entity
  @Table(name = "member")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  @DynamicInsert
  @DynamicUpdate
  public class MemberData extends BaseEntity {

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      @Column(name = "member_id")
      private Long memberId;

      @Column(name = "user_account_id", nullable = false)
      private Long userAccountId;

      @Column(name = "authority_tier", nullable = false, length = 8)
      @Enumerated(EnumType.STRING)
      private AuthorityTier authorityTier;

      @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
      @JoinColumn(name = "profile_id")
      private ProfileData profileData;

      @Column(name = "is_profile_updated", nullable = false)
      private boolean isProfileUpdated;

      // ActivityData is no longer owned by Member as a JPA association.
      // Application services query it directly via ActivityRepository.findByUserAccountId(...).
      // Rationale: Member's PK is now memberId (AUTO_INCREMENT), but user_activity.user_id
      // joins on the legacy UserId value (== user_account.user_id == member.user_account_id).
      // Sharper aggregate boundary; eliminates the cross-PK reconciliation footgun.
      // See plan §"activityDataMap mapping decision" for full rationale.

      @Builder(access = AccessLevel.PRIVATE)
      private MemberData(Long userAccountId, AuthorityTier authorityTier,
                         ProfileData profileData, boolean isProfileUpdated) {
          this.userAccountId = userAccountId;
          this.authorityTier = authorityTier;
          this.profileData = profileData;
          this.isProfileUpdated = isProfileUpdated;
      }

      public static MemberData createForUserAccount(Long userAccountId) {
          return MemberData.builder()
              .userAccountId(userAccountId)
              .authorityTier(AuthorityTier.AM)
              .isProfileUpdated(false)
              .build();
      }

      public void initializeProfile(ProfileData profileData) {
          this.profileData = profileData;
      }

      public void updateProfileBio(String nickName, String introduction) {
          this.profileData.updateBio(nickName, introduction);
          this.isProfileUpdated = true;
      }

      public void updateAvatarBody(AvatarBodyUri bodyUri, int positionX, int positionY) {
          this.profileData.updateAvatarBody(bodyUri, positionX, positionY);
      }

      public void updateAvatarFace(AvatarFaceUri uri) {
          this.profileData.updateAvatarFaceSingleBody(uri);
      }

      public void updateAvatarFace(AvatarFaceUri uri, FaceSourceType src,
                                   double offsetX, double offsetY, double scale) {
          this.profileData.updateAvatarFaceWithTransform(uri, src, offsetX, offsetY, scale);
      }

      public void updateAvatarIcon(AvatarIconUri uri) {
          this.profileData.updateAvatarIcon(uri);
      }

      public void updateWalletAddress(WalletAddress walletAddress) {
          this.profileData.updateWalletAddress(walletAddress);
          this.authorityTier = AuthorityTier.FM;
      }

      /**
       * Build a profile summary view. Activity scores are passed in by the
       * application service (which queries ActivityRepository directly) — this
       * entity does not own the activity collection.
       */
      public ProfileSummary getProfileSummary(List<ActivitySummary> activitySummaries) {
          var bio = this.profileData.getBio();
          var avatar = this.profileData.getAvatarSetting();
          return new ProfileSummary(
              bio != null ? bio.getNicknameValue() : null,
              bio != null ? bio.getIntroduction() : null,
              avatar.getAvatarBodyUri().getValue(),
              avatar.getAvatarCompositionType(),
              avatar.getCombinePositionX(),
              avatar.getCombinePositionY(),
              avatar.getOffsetX(),
              avatar.getOffsetY(),
              avatar.getScale(),
              avatar.getAvatarFaceUri().getValue(),
              avatar.getAvatarIconUri().getValue(),
              this.profileData.getWalletAddress().getValue(),
              activitySummaries
          );
      }
  }
  ```

  **Removed methods (compared to current `MemberData`):**
  - `initializeActivityMap(Map<ActivityType, ActivityData>)` — init service now persists `ActivityData` rows directly via `activityRepository.saveAll(...)`.
  - `updateDjScore(int delta)` — `UserActivityCommandService` operates on `ActivityData` directly via repository.
  - The no-arg `getProfileSummary()` overload — replaced with the explicit-list version. Existing callers either pass `List.of()` (when activity is irrelevant) or query activities first (when activity is shown).

  These removals will surface as compile errors at the existing callers. Document them in the Chunk 3 cascade section so they aren't missed.

- [ ] **Step 4: Run the MemberData test**

  Run: `./gradlew :user:test --tests "*MemberDataTest" --no-daemon`
  Expected: PASS for the unit-level assertions. Module-level compile may still fail due to call-sites — ignore for now.

- [ ] **Step 5: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java \
          user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/MemberDataTest.java
  git commit -m "refactor(iam): MemberData becomes Party-context aggregate

  - Drop extends UserAccountData; extend BaseEntity directly
  - PK: memberId BIGINT AUTO_INCREMENT (was: shared user_id)
  - userAccountId: cross-BC value reference (UNIQUE, no FK)
  - Absorb authorityTier, profileData, isProfileUpdated from old parent
  - Drop email, providerType (now on UserAccount)
  - Drop @OneToMany activityDataMap; ActivityData accessed via repository
    directly. Removes initializeActivityMap and updateDjScore methods.
    See plan for rationale (sharper aggregate boundary).
  - createForUserAccount(Long userAccountId) replaces old factories
  - getProfileSummary(activities) takes activity list as argument

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.3"
  ```

---

### Task 6: Refactor GuestData to composition

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/GuestData.java`
- Test: `user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/GuestDataTest.java` (new or updated)

**Background:** Same shape as MemberData. PK `guest_id` AUTO_INCREMENT, `userAccountId` value reference. Absorbs `profileData`, `isProfileUpdated`, `authorityTier` (set to `GT` in factory). Keeps `agent` field (nullable per V4 DDL). Drops `extends UserAccountData`. `agent` is allowed to be `null` — the factory accepts null and the test must reflect that. `Guest` does NOT carry `@AggregateRoot` in the current code (only `MemberData` does); preserve that.

- [ ] **Step 1: Write the failing test**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.enums.AuthorityTier;
  import org.junit.jupiter.api.Test;
  import static org.assertj.core.api.Assertions.assertThat;

  class GuestDataTest {

      @Test
      void createForUserAccount_defaultsToGtTierAndCapturesAgent() {
          var guest = GuestData.createForUserAccount(99L, "Firefox/MacOS");

          assertThat(guest.getUserAccountId()).isEqualTo(99L);
          assertThat(guest.getAuthorityTier()).isEqualTo(AuthorityTier.GT);
          assertThat(guest.getAgent()).isEqualTo("Firefox/MacOS");
          assertThat(guest.isProfileUpdated()).isFalse();
          assertThat(guest.getGuestId()).isNull(); // assigned on persist
      }

      @Test
      void createForUserAccount_acceptsNullAgent() {
          var guest = GuestData.createForUserAccount(99L, null);

          assertThat(guest.getAgent()).isNull();
          assertThat(guest.getAuthorityTier()).isEqualTo(AuthorityTier.GT);
      }

      @Test
      void initiateProfile_setsProfileAndMarksUpdated() {
          var guest = GuestData.createForUserAccount(99L, "Firefox");
          var profile = ProfileData.builder().build();

          guest.initiateProfile(profile);

          assertThat(guest.getProfileData()).isSameAs(profile);
          assertThat(guest.isProfileUpdated()).isTrue();
      }
  }
  ```

- [ ] **Step 2: Run the test (expect compile failure / red)**

  Run: `./gradlew :user:test --tests "*GuestDataTest" --no-daemon`
  Expected: COMPILATION FAILURE (`createForUserAccount` doesn't exist).

- [ ] **Step 3: Rewrite GuestData**

  ```java
  package com.pfplaybackend.api.user.domain.entity.data;

  import com.pfplaybackend.api.common.entity.BaseEntity;
  import com.pfplaybackend.api.common.enums.AuthorityTier;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Builder;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import org.hibernate.annotations.DynamicInsert;
  import org.hibernate.annotations.DynamicUpdate;

  @Entity
  @Table(name = "guest")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  @DynamicInsert
  @DynamicUpdate
  public class GuestData extends BaseEntity {

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      @Column(name = "guest_id")
      private Long guestId;

      @Column(name = "user_account_id", nullable = false)
      private Long userAccountId;

      @Column(name = "agent", length = 255)
      private String agent;

      @Column(name = "authority_tier", nullable = false, length = 8)
      @Enumerated(EnumType.STRING)
      private AuthorityTier authorityTier;

      @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
      @JoinColumn(name = "profile_id")
      private ProfileData profileData;

      @Column(name = "is_profile_updated", nullable = false)
      private boolean isProfileUpdated;

      @Builder(access = AccessLevel.PRIVATE)
      private GuestData(Long userAccountId, String agent, AuthorityTier authorityTier,
                        ProfileData profileData, boolean isProfileUpdated) {
          this.userAccountId = userAccountId;
          this.agent = agent;
          this.authorityTier = authorityTier;
          this.profileData = profileData;
          this.isProfileUpdated = isProfileUpdated;
      }

      public static GuestData createForUserAccount(Long userAccountId, String agent) {
          return GuestData.builder()
              .userAccountId(userAccountId)
              .agent(agent)
              .authorityTier(AuthorityTier.GT)
              .isProfileUpdated(false)
              .build();
      }

      public void initiateProfile(ProfileData profileData) {
          this.profileData = profileData;
          this.isProfileUpdated = true;
      }
  }
  ```

  **Schema-mapping note:** The V4 DDL for `guest` does **not** declare `authority_tier` or `profile_id` columns (compare to the spec §4.1.2 — only `guest_id`, `user_account_id`, `agent`, `created_at`, `updated_at` are present). However the existing `GuestData` carries `authorityTier` and `profileData` because guests can be promoted/profiled. **This is a real spec/code mismatch.** Resolve at task time:

  - **Option (preferred):** Update V4 DDL in Task 3 to add `authority_tier ENUM('FM','AM','GT') NOT NULL DEFAULT 'GT'`, `profile_id BIGINT UNSIGNED NULL`, `is_profile_updated BIT NOT NULL DEFAULT 0` to the `guest` table — preserving existing semantics. (V1 had these on the parent `user_account`; V4 must move them to both `member` and `guest` since composition splits the parent.)
  - **Option (alternative):** Drop `authorityTier`/`profileData` fields from `GuestData` here in Task 6 if the team decides Guest doesn't need them. This is a behavior change — confirm with the spec before choosing.

  **Implementer must pick one and document the choice in Step 5's commit message.** The spec §4.1.2 DDL appears to have an oversight on the guest table; the natural interpretation is "preserve V1 semantics" (the preferred option above). Update the V4 SQL file as part of this task (Step 3.5) if going with the preferred option.

- [ ] **Step 3.5 (only if preferred option chosen): Update V4 DDL for guest**

  Edit `app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql`. Within the `CREATE TABLE guest (...)` block, add immediately after `user_account_id`:

  ```sql
      authority_tier       ENUM('FM','AM','GT') NOT NULL DEFAULT 'GT',
      profile_id           BIGINT UNSIGNED NULL,
      is_profile_updated   BIT             NOT NULL DEFAULT 0,
  ```

  And add `CONSTRAINT fk_guest_profile FOREIGN KEY (profile_id) REFERENCES user_profile(id)` to the table (mirroring `member`).

  This is the only intra-PR DDL change after Task 3 — re-amend the V4 migration commit OR add a follow-up commit. Re-amend is cleaner for a single migration file.

- [ ] **Step 4: Run the test — expect green**

  Run: `./gradlew :user:test --tests "*GuestDataTest" --no-daemon`
  Expected: PASS (entity compiles; downstream code may still be broken — that's Chunk 3's job).

- [ ] **Step 5: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/GuestData.java \
          user/src/test/java/com/pfplaybackend/api/user/domain/entity/data/GuestDataTest.java \
          app/src/main/resources/db/migration/V4__refactor_user_account_to_iam.sql
  git commit -m "refactor(iam): GuestData becomes Party-context aggregate

  - extends BaseEntity (drop @Inheritance parent)
  - guestId BIGINT AUTO_INCREMENT PK; userAccountId value reference
  - Preserve authorityTier/profileData/isProfileUpdated semantics from V1
    (V4 DDL amended to add these columns on the guest table — they were
    on the parent user_account in V1)
  - createForUserAccount(userAccountId, agent) replaces old factories

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.3"
  ```

---

### Task 7: Refactor repositories

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/GuestRepository.java`
- Modify: any `*RepositoryCustom*` files in the same package
- Test: `@DataJpaTest` slices for the new query methods (Testcontainers MySQL)

**Background:** `email` / `providerType` lookups move to `UserAccountRepository`. `Member`/`Guest` queries gain `findByUserAccountId(Long)` and lose `findByUserId(UserId)` (since `UserId` is no longer a field on Member/Guest; the IAM identity is owned by UserAccount).

**`MemberRepositoryCustom` direction (resolved at plan-write time):** `MemberRepositoryImpl.findByUserId(UserId)` uses QueryDSL `qMemberData.userId.eq(userId)`. Since `MemberData.userId` no longer exists, the QueryDSL predicate must change to `qMemberData.userAccountId.eq(userAccountId)`. Decision: **rename** the custom method to `findByUserAccountId(Long)` and update the QueryDSL impl. Do NOT remove the custom interface — leave it as the seam for any future complex queries. Existing callers of `findByUserId(UserId)` are patched in Chunk 3 (covered as part of the call-site cascade).

- [ ] **Step 1: Read the existing repository files (ground the implementer)**

  `Read` these four files before writing any changes — needed for the QueryDSL impl details and any field-level annotations to preserve:
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepositoryCustom.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/impl/MemberRepositoryImpl.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/GuestRepository.java`
  - `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/GuestRepositoryCustom.java` (if it exists)

  If any QueryDSL Q-class references appear (`QMemberData`), note that those are auto-regenerated by the Gradle build — they don't need manual editing, but a `:user:compileJava` is needed to refresh them after the entity changes in Tasks 4–6 land.

- [ ] **Step 2: Write a `@DataJpaTest` integration test that exercises the new methods**

  ```java
  // Place in: app/src/test/java/com/pfplaybackend/api/iam/IamRepositoryIntegrationTest.java
  // (Use existing Testcontainers config — see app/src/test/.../config/TestContainerConfig.java)

  @DataJpaTest
  @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
  @Import(TestContainerConfig.class)
  class IamRepositoryIntegrationTest {

      @Autowired UserAccountRepository userAccountRepo;
      @Autowired MemberRepository memberRepo;
      @Autowired GuestRepository guestRepo;

      @Test
      void findByEmailAndProviderType_returnsAccount() {
          var saved = userAccountRepo.save(
              UserAccountData.createForSocial(new UserId(101L), "alice@gmail.com", ProviderType.GOOGLE));

          var found = userAccountRepo.findByEmailAndProviderType("alice@gmail.com", ProviderType.GOOGLE);

          assertThat(found).isPresent();
          assertThat(found.get().getUserId().getUid()).isEqualTo(101L);
      }

      @Test
      void existsByEmail_handlesDuplicates() {
          userAccountRepo.save(UserAccountData.createForSocial(
              new UserId(102L), "bob@gmail.com", ProviderType.GOOGLE));

          assertThat(userAccountRepo.existsByEmail("bob@gmail.com")).isTrue();
          assertThat(userAccountRepo.existsByEmail("nope@gmail.com")).isFalse();
      }

      @Test
      void member_findByUserAccountId_returnsMember() {
          userAccountRepo.save(UserAccountData.createForSocial(
              new UserId(200L), "carol@gmail.com", ProviderType.GOOGLE));
          memberRepo.save(MemberData.createForUserAccount(200L));

          var found = memberRepo.findByUserAccountId(200L);

          assertThat(found).isPresent();
          assertThat(found.get().getUserAccountId()).isEqualTo(200L);
      }
  }
  ```

  Adapt to the existing `@DataJpaTest` + Testcontainers boot conventions — do not invent new patterns. If existing tests use a different harness (e.g., `@SpringBootTest` slice), match that.

- [ ] **Step 3: Run the test (expect compile failures / red)**

- [ ] **Step 4: Update UserAccountRepository**

  ```java
  package com.pfplaybackend.api.user.adapter.out.persistence;

  import com.pfplaybackend.api.common.config.security.enums.ProviderType;
  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
  import org.springframework.data.jpa.repository.JpaRepository;

  import java.util.Optional;

  public interface UserAccountRepository extends JpaRepository<UserAccountData, UserId> {
      Optional<UserAccountData> findByUserId(UserId userId);
      Optional<UserAccountData> findByEmail(String email);
      Optional<UserAccountData> findByEmailAndProviderType(String email, ProviderType providerType);
      boolean existsByEmail(String email);
  }
  ```

- [ ] **Step 5: Update MemberRepository (Spring Data interface)**

  Remove these methods (UserAccount owns the data now):
  - `findByEmail(String): Optional<MemberData>`
  - `countByProviderType(ProviderType): long`

  Add:
  - `findByUserAccountId(Long userAccountId): Optional<MemberData>`
  - `existsByUserAccountId(Long userAccountId): boolean`

- [ ] **Step 6: Update MemberRepositoryCustom + MemberRepositoryImpl (QueryDSL)**

  - Rename interface method: `findByUserId(UserId)` → `findByUserAccountId(Long)`.
  - Update `MemberRepositoryImpl` predicate: `qMemberData.userId.eq(userId)` → `qMemberData.userAccountId.eq(userAccountId)`.
  - Confirm Q-class regenerates after `./gradlew :user:compileJava` (the entity-side `userAccountId` field gives QueryDSL the new path).

- [ ] **Step 7: Update GuestRepository (and Custom if present)**

  - Spring Data interface: remove `findGuestByUserId(UserId)`; add `findByUserAccountId(Long): Optional<GuestData>`.
  - If `GuestRepositoryCustom` exists with a `findByUserId(UserId)`, rename to `findByUserAccountId(Long)` and update its impl.
  - If no custom interface exists for Guest, skip.

- [ ] **Step 8: Run the integration test**

  Run: `./gradlew :app:test --tests "*IamRepositoryIntegrationTest" --no-daemon`
  Expected: PASS. (Note: this requires Testcontainers + Docker. If unavailable, document and defer to Chunk 4 smoke test.)

- [ ] **Step 9: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java \
          user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/MemberRepository*.java \
          user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/GuestRepository*.java \
          app/src/test/java/com/pfplaybackend/api/iam/IamRepositoryIntegrationTest.java
  git commit -m "refactor(iam): repositories aligned with composition model

  - UserAccountRepository: + findByEmail, findByEmailAndProviderType,
    existsByEmail
  - MemberRepository: drop findByEmail / countByProviderType (UserAccount
    owns these now), + findByUserAccountId
  - GuestRepository: + findByUserAccountId

  Integration test in :app validates query semantics against
  Testcontainers MySQL.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.3"
  ```

---

## Chunk 2 Checkpoint

After Task 7, the entity + repository layer is internally consistent but the application layer still references old shapes (`MemberData.createWithFixedUserId`, `member.getEmail()`, `member.getProviderType()`, etc.). `./gradlew :user:compileJava` and `./gradlew :app:compileJava` both fail. **This is expected.** Chunk 3 systematically patches every call-site to restore the green build.

---

## Chunk 3: Application Layer Cascade

This chunk patches every call-site that broke in Chunk 2. Tasks proceed bottom-up: low-level services first (so their consumers can be patched next), then the OAuth/admin flows that orchestrate them. **Compilation should be restored fully by the end of Task 12.**

**Discovery technique:** Before each task, run `./gradlew compileJava 2>&1 | head -100` and let the compiler tell you exactly which call-sites broke. Use that as the working list. The plan below lists the major files known at plan-write time, but minor unanticipated breakages are normal — the implementer triages as encountered.

### Task 8: Refactor OAuth login flow (AuthService + MemberSignService)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java`
- Modify: any companion `*ServiceTest.java` files

**Background:** Pre-V4, OAuth login looked up an existing `Member` (or created one) by `(email, providerType)` directly on `MemberRepository`. Post-V4, that lookup moves to `UserAccountRepository`, and creation becomes two-stage: persist `UserAccount` first, then persist a `Member` keyed by `userAccountId`. JWT claims are also sourced differently: `email` comes from `UserAccount`, `authorityTier` from `Member`.

**Two-stage creation pattern (to be applied consistently across Tasks 8–10):**

```java
// 1) Look up or create UserAccount
var userAccount = userAccountRepository
    .findByEmailAndProviderType(email, providerType)
    .orElseGet(() -> {
        var fresh = UserAccountData.createForSocial(new UserId(idGenerator.next()), email, providerType);
        return userAccountRepository.save(fresh);
    });

// 2) Look up or create Member keyed by userAccountId
var member = memberRepository
    .findByUserAccountId(userAccount.getUserId().getUid())
    .orElseGet(() -> {
        var fresh = MemberData.createForUserAccount(userAccount.getUserId().getUid());
        return memberRepository.save(fresh);
    });
```

**`UserId` allocation:** Currently the legacy `UserId` is constructed via `new UserId()` (no-arg constructor presumably auto-generates). Verify by reading `common/.../domain/value/UserId.java`. If it has a generator, keep using it. If not, find the existing id-generation mechanism. Don't invent a new strategy here.

- [ ] **Step 1: Read the surrounding code**

  `Read`:
  - `app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java` (focus on lines 40–80 — the OAuth login orchestration)
  - `user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java`
  - `common/src/main/java/com/pfplaybackend/api/common/domain/value/UserId.java`
  - Existing test files for both services (`AuthServiceTest`, `MemberSignServiceTest` if they exist)

- [ ] **Step 2: Update existing tests first (TDD)**

  Modify the existing `MemberSignServiceTest` (or create one if missing) to assert the new two-stage creation:
  - Test 1: When `UserAccountRepository` returns empty, both UserAccount and Member are persisted.
  - Test 2: When `UserAccountRepository` finds an existing account and Member exists, neither is re-saved.
  - Test 3: When `UserAccountRepository` finds an existing account but Member does not exist (recovery scenario), Member is saved.

  Use Mockito for repositories, plain JUnit 5.

- [ ] **Step 3: Run the test suite — expect failure**

  Run: `./gradlew :user:test --tests "*MemberSignServiceTest" --no-daemon`
  Expected: failures (test asserts behavior the service doesn't yet implement).

- [ ] **Step 4: Refactor `MemberSignService.getMemberOrCreate(...)`**

  Replace the body so it returns a `Member` after performing the two-stage lookup-or-create above. Inject `UserAccountRepository` if not already present. Signature change suggestion:

  ```java
  public MemberData getMemberOrCreate(String email, ProviderType providerType) {
      var userAccount = userAccountRepository.findByEmailAndProviderType(email, providerType)
          .orElseGet(() -> userAccountRepository.save(
              UserAccountData.createForSocial(new UserId(), email, providerType)));

      userAccount.recordLogin(); // updates last_login_at — JPA flush at tx end persists this
      // (recordLogin mutates the managed entity; no explicit save() needed)

      return memberRepository.findByUserAccountId(userAccount.getUserId().getUid())
          .orElseGet(() -> memberRepository.save(
              MemberData.createForUserAccount(userAccount.getUserId().getUid())));
  }
  ```

  **Note on `recordLogin()`:** Lifts `last_login_at` write into the OAuth path. Per spec §4.1.1 (7), this is the desired behavior. JPA dirty-checking handles persistence on commit, **but only inside an active transaction**. Step 4a verifies this.

- [ ] **Step 4a: Verify `@Transactional` propagation for `recordLogin()`**

  Without an active transaction, `userAccount.recordLogin()` mutates the entity but the change is never flushed to DB. The plan ships green-compile + green-tests but `last_login_at` silently never updates in production.

  Verify:
  - `MemberSignService.getMemberOrCreate(...)` is annotated `@Transactional`, OR
  - Its caller `AuthService.signIn(...)` is `@Transactional`.

  If neither is, add `@Transactional` to `MemberSignService.getMemberOrCreate`. If both already are, no change.

  Add a unit test (or extend the existing one) that asserts `lastLoginAt` is non-null after a successful `getMemberOrCreate` call against an existing UserAccount — use `@DataJpaTest` slice or full `@SpringBootTest` to exercise transaction boundaries (Mockito alone won't catch this regression).

- [ ] **Step 5: Refactor `AuthService.signIn` (or whichever method handles OAuth callback)**

  The change is at lines ~53 and ~60 (verified by Explore agent earlier):
  - Line 53: `memberSignService.getMemberOrCreate(email, ProviderType.valueOf(provider.name()))` — signature unchanged but the underlying behavior is now two-stage. No edit needed at the call-site itself.
  - Line 60 (JWT claim assembly): `new TokenClaimsRequest(member.getUserId(), member.getEmail(), AccessLevel.ROLE_MEMBER, member.getAuthorityTier())` no longer compiles because `Member` has no `getEmail()` and `Member.getUserId()` doesn't exist (replaced by `getUserAccountId()`).

  Patch:

  ```java
  // Before:
  // var claims = new TokenClaimsRequest(
  //     member.getUserId().getUid().toString(),
  //     member.getEmail(),
  //     AccessLevel.ROLE_MEMBER,
  //     member.getAuthorityTier());

  // After:
  var userAccount = userAccountRepository.findById(new UserId(member.getUserAccountId()))
      .orElseThrow(() -> new IllegalStateException("UserAccount missing for member " + member.getMemberId()));
  var claims = new TokenClaimsRequest(
      member.getUserAccountId().toString(),
      userAccount.getEmail(),
      AccessLevel.ROLE_MEMBER,
      member.getAuthorityTier());
  ```

  This adds an extra `UserAccount` fetch per sign-in. If profiling later shows it's hot, add a `findByUserAccountIdJoinFetchAccount` query method. For PR 1 the simpler shape is acceptable.

  Inject `UserAccountRepository` into `AuthService` if not already present.

- [ ] **Step 6: Run AuthService + MemberSignService tests**

  Run: `./gradlew :app:test --tests "*AuthServiceTest" :user:test --tests "*MemberSignServiceTest" --no-daemon`
  Expected: PASS.

- [ ] **Step 7: Commit**

  ```bash
  git add app/src/main/java/com/pfplaybackend/api/auth/application/service/AuthService.java \
          user/src/main/java/com/pfplaybackend/api/user/application/service/MemberSignService.java \
          app/src/test/java/com/pfplaybackend/api/auth/application/service/AuthServiceTest.java \
          user/src/test/java/com/pfplaybackend/api/user/application/service/MemberSignServiceTest.java
  git commit -m "refactor(iam): OAuth flow uses two-stage UserAccount+Member creation

  - MemberSignService.getMemberOrCreate looks up by (email, providerType)
    via UserAccountRepository, creates UserAccount + Member as needed.
  - AuthService sources email from UserAccount and authorityTier from
    Member when assembling JWT claims (Member no longer carries email).
  - UserAccount.recordLogin() updates last_login_at on each sign-in.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.4"
  ```

---

### Task 9: Refactor init services (admin + temporary user bootstrap)

**Files:**
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/AdminUserInitializeService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/TemporaryUserInitializeService.java`

**Background:** These services bootstrap fixed-id users at app startup (`ApplicationReadyEvent`). Pre-V4 they used `MemberData.createWithFixedUserId(...)` which simultaneously created a row in `user_account` (parent) and `member` (child). Post-V4 they must persist UserAccount first, then Member/Guest.

**Why admin keeps `ProviderType.GOOGLE` in this PR:** Spec §4.1 keeps the existing admin-bootstrap behavior unchanged. PR 2 (`V5`) replaces this entire path with a DDL-level `INSERT` of the SUPER_ADMIN with `ProviderType.LOCAL` and the addAdminUser hook gets removed. Don't try to migrate admin to LOCAL in this PR — it will conflict with PR 2's seed. Virtual users (Task 10) DO migrate to LOCAL because they aren't replaced by V5.

- [ ] **Step 1: Read both files end-to-end**

  Both are short (Explore agent reported lines 33–63 area). Read them in full to understand the existing call ordering, profile init sub-routine, and any activity-map setup that needs replacement.

- [ ] **Step 2: Refactor `AdminUserInitializeService.addAdminUser()`**

  Pattern:

  ```java
  public void addAdminUser() {
      if (userAccountRepository.findByUserId(adminUserId).isPresent()) {
          return; // idempotent
      }

      // 1) Persist UserAccount
      var userAccount = UserAccountData.createForSocial(adminUserId, "N/A", ProviderType.GOOGLE);
      userAccountRepository.save(userAccount);

      // 2) Persist Member with fixed userAccountId == adminUserId.uid
      var member = MemberData.createForUserAccount(adminUserId.getUid());
      // initialize profile + activity rows as before
      member.initializeProfile(profileFor(member));
      memberRepository.save(member);

      // 3) Persist activity rows directly (instead of via member.initializeActivityMap)
      activityRepository.saveAll(buildActivityRows(adminUserId.getUid()));

      // 4) Wallet promotion (existing behavior)
      member.updateWalletAddress(adminWalletAddress);
  }
  ```

  - The `adminUserId` is the same fixed `UserId` value used pre-V4 — this preserves the V5/V7 seed assumptions. `adminUserId.getUid()` becomes `userAccountId` for Member.
  - Replace `member.initializeActivityMap(buildActivityMap())` with `activityRepository.saveAll(buildActivityRows(...))`. The helper method that previously returned a `Map<ActivityType, ActivityData>` now returns a `List<ActivityData>` keyed by `userAccountId`.
  - `ActivityData.create(...)` factory currently takes `UserId`. Either keep that signature (the value is the same) or add `ActivityData.createForUserAccount(Long, ActivityType, int)`. **Decision: keep current `UserId` signature** — `ActivityData` retains `userId` as its FK field per the activityDataMap removal note in Task 5.

- [ ] **Step 3: Refactor `TemporaryUserInitializeService`**

  Two paths to update:

  - `addAssociateMember(userId, email)`: same two-stage pattern as admin (UserAccount + Member, ProviderType.GOOGLE).
  - `addGuest(userId)`: similar two-stage but creates `GuestData.createForUserAccount(userId.getUid(), agent)`. UserAccount for guests still needs a placeholder email — use `"guest-" + userId.getUid() + "@guest.local"` to satisfy the `email NOT NULL UNIQUE` constraint.

  ```java
  // addGuest sketch:
  var ua = UserAccountData.createForSocial(userId,
      "guest-" + userId.getUid() + "@guest.local",
      ProviderType.GOOGLE); // guests use GOOGLE placeholder per pre-V4 behavior
  userAccountRepository.save(ua);
  var guest = GuestData.createForUserAccount(userId.getUid(), "Firefox/MacOS");
  guestRepository.save(guest);
  ```

  **Wait — guests with a synthetic email may collide with real Google accounts named `guest-X@guest.local`.** Vanishingly unlikely but the placeholder email pattern should be unmistakably synthetic. Use `@guest.local` (a reserved-by-convention TLD) or document the choice. The rest of the spec accepts loose-ref synthetic emails so this is fine.

- [ ] **Step 4: Run init service tests if any exist; otherwise rely on Chunk 4 boot smoke test**

  Run: `./gradlew :user:test --tests "*InitializeService*" --no-daemon`
  If no tests exist for these: skip the test gate, verify behavior in Chunk 4.

- [ ] **Step 5: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/application/service/initialize/
  git commit -m "refactor(iam): init services use two-stage UserAccount+Member creation

  - AdminUserInitializeService: UserAccount(GOOGLE) + Member with fixed
    adminUserId; activity rows persisted directly via activityRepository
    instead of member.initializeActivityMap.
  - TemporaryUserInitializeService: same two-stage shape for both
    members and guests; guest UserAccount uses guest-{id}@guest.local
    placeholder email.

  Admin user keeps ProviderType.GOOGLE for now — V5 (PR 2) replaces this
  bootstrap path with a DDL-level SUPER_ADMIN seed using LOCAL.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.4"
  ```

---

### Task 10: Refactor admin virtual member flow (ProviderType.LOCAL)

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminUserService.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/service/AdminDemoService.java`
- Modify: `app/src/main/java/com/pfplaybackend/api/admin/application/port/out/AdminMemberPort.java` and its adapter

**Background:** Virtual members (admin-created users for stage hosting / demo content) currently use `ProviderType.ADMIN`. Per spec §4.1.1 (6), this becomes `LOCAL`. This task migrates every virtual-member call-site from `ADMIN` to `LOCAL` while also adapting to the two-stage UserAccount+Member creation. After this task, no production code references `ADMIN` — but the enum value is left in place until Chunk 4 Task 13 for safety.

- [ ] **Step 1: Inventory virtual-member call-sites**

  Run `Grep` for `ProviderType.ADMIN` across the codebase. Expected hits per Explore agent's earlier report:
  - `AdminUserService.java:65, 122, 157, 177`
  - `AdminDemoService.java:398`
  - `AdminMemberPort.java` and its adapter
  - Possibly tests

  Write the full list down before editing.

- [ ] **Step 2: Update `AdminUserService.createVirtualMember`**

  Apply the two-stage pattern with LOCAL. Per spec §4.1.2, `password_hash` is nullable — virtual users have no real login, so they store `null`. (Defined `UserAccountData.createForLocal(UserId, String, String)` factory in Chunk 2 Task 4 — the `passwordHash` parameter accepts `null`.)

  ```java
  // In createVirtualMember(...):
  var virtualEmail = generateVirtualEmail(...); // existing logic, unchanged
  var ua = UserAccountData.createForLocal(new UserId(), virtualEmail, null); // virtual user — no password
  userAccountRepository.save(ua);

  var member = MemberData.createForUserAccount(ua.getUserId().getUid());
  member.initializeProfile(buildProfileFor(...));
  memberRepository.save(member);
  ```

  **`createForLocal` validation:** Confirm the factory in Task 4 accepts `null` passwordHash without throwing. If it currently rejects null (e.g., explicit non-null check), relax to allow null OR add a separate `createForLocalVirtual(UserId, String)` factory. Either is fine; document the choice in the commit. The DDL allows `null`, so domain-layer rejection of `null` would be a stricter-than-DDL invariant — which is acceptable but worth being explicit about.

- [ ] **Step 3: Update `member.getProviderType()` comparison sites**

  Lines 122, 157, 177 in `AdminUserService.java` compared `member.getProviderType() != ProviderType.ADMIN` to gate "is this a virtual member?" checks. Two issues now:
  - `Member` no longer has `getProviderType()` — must look up `UserAccount`.
  - The value to compare against changes from `ADMIN` to `LOCAL`.

  Patch the three sites:

  ```java
  // Replace:
  // if (member.getProviderType() != ProviderType.ADMIN) { throw ...; }

  // With:
  var ua = userAccountRepository.findById(new UserId(member.getUserAccountId()))
      .orElseThrow(() -> new IllegalStateException("UserAccount missing for member " + member.getMemberId()));
  if (ua.getProviderType() != ProviderType.LOCAL) {
      throw new IllegalStateException("Operation only allowed on virtual (LOCAL) members");
  }
  ```

  If the per-method extra fetch is hot, factor a private helper `requireLocalProviderForVirtualMemberOp(member)`. Keep the helper local to this service.

- [ ] **Step 4: Update `AdminUserService.java:99` log line + line 139–145 profile data access**

  - Line 99: `finalData.getAuthorityTier()` — `Member.getAuthorityTier()` still works (authorityTier moved INTO Member in Task 5). No change needed.
  - Lines 139–145: `member.getProfileData().getAvatarSetting()` etc. — `profileData` is now on `Member`. No change.

  These should compile as-is once Tasks 5–7 land.

- [ ] **Step 5: Update `AdminMemberPort.countMembersByProviderType(...)` and its adapter**

  The port method signature is unchanged (still takes `ProviderType`), but the implementation changes:
  - Old impl: `memberRepository.countByProviderType(providerType)`.
  - New impl: `userAccountRepository.countByProviderType(providerType)`. (Add this method to `UserAccountRepository` — Spring Data derives it automatically from the field name.)

  Update the adapter accordingly.

- [ ] **Step 6: Update `AdminDemoService.java:398`**

  ```java
  // Replace:
  // adminMemberPort.countMembersByProviderType(ProviderType.ADMIN)

  // With:
  adminMemberPort.countMembersByProviderType(ProviderType.LOCAL)
  ```

- [ ] **Step 7: Run admin tests**

  Run: `./gradlew :app:test --tests "Admin*" --no-daemon`
  Expected: PASS. Existing tests against `AdminUserService` / `AdminDemoService` may have hardcoded `ADMIN` expectations — update them too.

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/main/java/com/pfplaybackend/api/admin/ \
          user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/UserAccountRepository.java \
          app/src/test/java/com/pfplaybackend/api/admin/
  git commit -m "refactor(iam): admin virtual member uses ProviderType.LOCAL

  - createVirtualMember now persists UserAccount(LOCAL) + Member
    (two-stage); password_hash is an unmistakable placeholder.
  - getProviderType() comparisons resolve via UserAccount lookup
    (Member no longer carries providerType).
  - countMembersByProviderType implementation moves from
    MemberRepository to UserAccountRepository.
  - All ADMIN→LOCAL migrations land here; ADMIN enum value removed in
    Task 13 after final compile check.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.1 (6)"
  ```

---

### Task 11: Create ActivityRepository + refactor activity-data flow

**Files:**
- Create: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/persistence/ActivityRepository.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/application/service/UserActivityCommandService.java`
- Modify: `user/src/main/java/com/pfplaybackend/api/user/adapter/out/event/UserDomainEventRelay.java`
- Modify: `user/src/test/java/com/pfplaybackend/api/user/application/service/UserAvatarQueryServiceTest.java`
- Modify: any other call-sites surfaced by `Grep` (see Step 1)

**Background:** Task 5 dropped `MemberData.activityDataMap` and the `updateDjScore`/`initializeActivityMap` methods. **There is currently no standalone `ActivityRepository`** — `ActivityData` was only ever loaded through the JPA association on `MemberData`. This task creates the repository and migrates all consumers.

**Verified codebase facts:**
- `ActivityData` (line 33–34 of `ActivityData.java`) carries `@Embedded UserId userId` mapped to `@Column(name = "user_id")`. Its `addScore(int)` method exists and works as expected.
- The legacy `UserId` VO value equals `member.userAccountId` by construction: every UserAccount factory uses `new UserId()` (auto-generated) or a fixed `UserId`, and `Member.createForUserAccount(Long)` is always called with `userAccount.getUserId().getUid()`. Activity rows are written with the same `UserId`. Spring Data derivation on the embedded `userId` therefore matches by value.

- [ ] **Step 1: Inventory ALL call-sites of the broken patterns (not just activity-related)**

  Run these greps and write down every hit:
  - `Grep "getActivityDataMap|activityDataMap|updateDjScore|initializeActivityMap" --glob "**/*.java"`
  - `Grep "getProfileSummary\\(\\)" --glob "**/*.java"` (no-arg overload removed)
  - `Grep "memberRepository\\.findByUserId|memberRepository\\.findByUserAccountId" --glob "**/*.java"` — find every consumer of the renamed method
  - `Grep "guestRepository\\.findGuestByUserId|guestRepository\\.findByUserId" --glob "**/*.java"` — same for Guest
  - `Grep "member\\.getEmail\\(\\)|member\\.getProviderType\\(\\)" --glob "**/*.java"` — Member no longer has these
  - `Grep "user_type" --glob "**/*.{java,sql,xml}"` — discriminator removal per spec §4.1.4 item 4

  Expected hits per spec §4.1.4 + verified codebase exploration:
  - `UserAvatarCommandService.java` — `memberRepository.findByUserId(authContext.getUserId())`
  - `UserBioCommandService.java` — same pattern
  - `UserWalletCommandService.java` — same
  - `UserProfileQueryService.java` — multiple Member + Guest find-by-userId calls
  - `UserAvatarQueryService.java` — same
  - `UserInfoQueryService.java` — `userAccountRepository.findByUserId(...)` (this one stays — UserAccount keeps `findByUserId(UserId)`)
  - `UserActivityCommandService.java` — uses `activityDataMap` indirectly
  - `UserAvatarQueryServiceTest.java` — test fixture using `activityDataMap`
  - `UserDomainEventRelay.java` — uses `findByUserId`
  - Possibly `user_type` discriminator references in QueryDSL/JPQL (should be zero in production code per Explore agent's earlier report; verify)

  **This is a substantial list.** Steps 2–5 below cover the activity-specific surgery plus a representative subset of the find-by-user migrations. Task 12 mops up anything Task 11 doesn't touch. **If during Step 2 you discover more than ~15 affected files, escalate as DONE_WITH_CONCERNS to flag the cascade is larger than estimated.**

- [ ] **Step 2: Create ActivityRepository**

  ```java
  package com.pfplaybackend.api.user.adapter.out.persistence;

  import com.pfplaybackend.api.common.domain.value.UserId;
  import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
  import com.pfplaybackend.api.user.domain.enums.ActivityType;
  import org.springframework.data.jpa.repository.JpaRepository;

  import java.util.List;
  import java.util.Optional;

  public interface ActivityRepository extends JpaRepository<ActivityData, Long> {
      Optional<ActivityData> findByUserIdAndActivityType(UserId userId, ActivityType activityType);
      List<ActivityData> findAllByUserId(UserId userId);
  }
  ```

  Spring Data derives both methods from the embedded `userId` field by value-comparing the `UserId.uid` column. No custom QueryDSL needed for these.

- [ ] **Step 3: Refactor `UserActivityCommandService.updateDjScore(...)` (or equivalent)**

  Old shape (likely):
  ```java
  var member = memberRepository.findByUserId(userId).orElseThrow();
  member.updateDjScore(delta); // dirty-flush via JPA
  ```

  New shape:
  ```java
  // userAccountId == legacy UserId.uid by construction; see Task 8 createForSocial flow
  var activity = activityRepository
      .findByUserIdAndActivityType(userId, ActivityType.DJ_PNT)
      .orElseThrow(() -> new IllegalStateException("DJ_PNT activity row missing for " + userId.getUid()));
  activity.addScore(delta);
  // dirty-flush via JPA (requires @Transactional on the calling service method)
  ```

  Inject `ActivityRepository` into `UserActivityCommandService`. Confirm `@Transactional` is on the method or class.

- [ ] **Step 4: Refactor `UserDomainEventRelay`**

  Pre-existing line 24:
  ```java
  MemberData member = memberRepository.findByUserId(event.getUserId()).orElseThrow();
  ```

  Replace with:
  ```java
  // userAccountId equals UserAccount.userId.uid by construction (see Task 8)
  MemberData member = memberRepository.findByUserAccountId(event.getUserId().getUid()).orElseThrow();
  ```

- [ ] **Step 5: Refactor `getProfileSummary()` callers**

  `Grep` for `getProfileSummary()` (no-arg). Each call must now provide an activity-summary list:

  ```java
  // Before:
  var summary = member.getProfileSummary();

  // After:
  var activities = activityRepository.findAllByUserId(userId).stream()
      .map(a -> new ActivitySummary(a.getActivityType(), a.getScore().getValue()))
      .toList();
  var summary = member.getProfileSummary(activities);
  ```

  If a caller is in a tight loop, batch-load via `findAllByUserIdIn(List<UserId>)` instead. For PR 1 the simple shape is fine.

- [ ] **Step 6: Migrate other `memberRepository.findByUserId(...)` consumers**

  For each file flagged in Step 1 (UserAvatarCommandService, UserBioCommandService, UserWalletCommandService, UserProfileQueryService, UserAvatarQueryService, etc.):
  - Replace `memberRepository.findByUserId(userId)` with `memberRepository.findByUserAccountId(userId.getUid())`.
  - For services that need both Member AND UserAccount (e.g., for email), inject `UserAccountRepository` and load both.
  - For Guest equivalents, replace `guestRepository.findGuestByUserId(userId)` with `guestRepository.findByUserAccountId(userId.getUid())`.

  These are mechanical one-line replacements. Treat as a batch — make every change, then compile once.

- [ ] **Step 7: Update broken tests (`UserAvatarQueryServiceTest` and others)**

  - `UserAvatarQueryServiceTest` sets `activityDataMap` on a Member fixture. Replace with: build the test Member via `createForUserAccount`, mock `ActivityRepository.findAllByUserId(userId)` to return the desired test rows, and verify the service's call into `getProfileSummary(activities)`.
  - Any other service tests broken by the `findByUserId → findByUserAccountId` rename: update mocks to stub the new method name.

- [ ] **Step 8: Run user-module tests**

  Run: `./gradlew :user:test --no-daemon`
  Expected: PASS.

- [ ] **Step 9: Commit**

  ```bash
  git add user/src/main/java/com/pfplaybackend/api/user/ \
          user/src/test/java/com/pfplaybackend/api/user/
  git commit -m "refactor(iam): activity data accessed directly, not via Member

  - UserActivityCommandService.updateDjScore queries ActivityRepository.
  - UserDomainEventRelay.findByUserId → findByUserAccountId.
  - getProfileSummary() callers fetch activity rows externally and pass
    a List<ActivitySummary> to the new entity method signature.
  - Test fixtures rebuilt against the composition model.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.4"
  ```

---

### Task 12: Restore green build — patch remaining call-sites

**Files:** Whatever `./gradlew compileJava` still reports as broken.

**Background:** Tasks 8–11 cover the major flows. This task is the residue — small queries, response DTOs, controllers — anything missed. The implementer iterates: compile → fix → compile until clean.

Known residue (from earlier inventory + spec §4.1.4):
- `QueryMyInfoResponse.java:22` — `user.getEmail()` — pass `UserAccount` (or its email) into the DTO instead of `Member`.
- `AdminUserController.java:139–145` — already covered by Task 10 (profileData access on Member is unchanged); confirm.
- `MemberRepositoryImpl.java` — Task 7 Step 6 renamed the QueryDSL predicate. Verify `qMemberData.userAccountId.eq(userId.getUid())` compiles after the Q-class regenerates.
- `GuestRepositoryImpl.java` (if it exists) — Task 7 Step 7 mentioned this only conditionally. Confirm: `Read` `user/.../persistence/impl/GuestRepositoryImpl.java`. If it exists with a `findByUserId(UserId)` predicate, rename method + rewrite predicate to `qGuestData.userAccountId.eq(userAccountId)`.
- `UserActivityCommandService` — covered by Task 11.
- **`user_type` discriminator removal (spec §4.1.4 item 4):** Run `Grep "user_type|userType|dtype" --glob "**/*.{java,sql,xml,properties,yaml,yml}"` and remove every reference except (a) the V1 Flyway file which is immutable history, and (b) the V12 spec document. JPA inheritance is gone; any stray reference will fail at runtime.

- [ ] **Step 1: Run a full compile and dump the error list**

  Run: `./gradlew compileJava 2>&1 | tee /tmp/compile-errors.log` (Windows: redirect via PowerShell `Tee-Object`).
  Read the log and triage.

- [ ] **Step 2: Patch each error in turn**

  General rules:
  - `member.getEmail()` / `member.getProviderType()` — replace with a `UserAccount` lookup, hold in a local var if the same call-site needs it twice.
  - `member.getUserId()` — replace with `member.getUserAccountId()` (returns `Long`); wrap in `new UserId(...)` only if the consumer truly needs the VO.
  - `MemberData.createWithFixedUserId(...)` / `MemberData.create(...)` — replace with the two-stage pattern from Task 8.
  - `GuestData.createWithFixedUserId(...)` / `GuestData.create()` — same pattern.
  - `findByUserId(UserId)` on member/guest repos — `findByUserAccountId(userId.getUid())`.

- [ ] **Step 3: Run the full test suite**

  Run: `./gradlew test --no-daemon`
  Expected: PASS. Failures in unit tests should be small fixture updates; failures in integration/Testcontainers tests indicate real semantic regressions and need deeper investigation.

- [ ] **Step 4: Run ArchUnit**

  Run: `./gradlew test --tests "*HexagonalArchitectureTest" --no-daemon`
  Expected: PASS. The composition refactor doesn't introduce new cross-module imports — but verify.

- [ ] **Step 5: Commit (may be split into multiple commits if the residue is large)**

  ```bash
  git add <whatever>
  git commit -m "refactor(iam): patch remaining call-sites for composition

  Residual fixes after the major flows: response DTOs, controllers, and
  test fixtures resolved against the new entity shape. Full test suite
  green; ArchUnit clean.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.4"
  ```

---

## Chunk 3 Checkpoint

After Task 12, the entire codebase compiles cleanly and all existing tests pass. The only remaining work is the boot/migration smoke test (Chunk 4) and final cleanup (`ProviderType.ADMIN` removal).

---

## Chunk 4: Cleanup + Verification

This chunk removes the deprecated `ADMIN` enum value, runs ArchUnit to verify no architecture regression, and performs an end-to-end boot smoke test against a real MySQL container — exactly the same harness used to verify PR 0.

### Task 13: Remove ProviderType.ADMIN

**Files:**
- Modify: `common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java`

**Background:** Task 1 added `LOCAL` while keeping `ADMIN` for source compatibility. Task 10 migrated every production call-site from `ADMIN` to `LOCAL`. This task asserts via `Grep` that nothing references `ADMIN` anymore, then removes the enum value.

- [ ] **Step 1: Final inventory of ADMIN references**

  Run a broad grep that catches all enum reference shapes:
  - `Grep "ProviderType\\.ADMIN" --glob "**/*.java"` — direct references
  - `Grep "import static .+ProviderType\\.ADMIN" --glob "**/*.java"` — static imports
  - `Grep "\"ADMIN\"" --glob "**/*.{sql,yaml,yml,properties,json}"` — config/test-fixture string literals
  - `Grep "provider_type.*ADMIN|provider-type.*ADMIN|providerType.*ADMIN" --glob "**/*"` — context-sensitive non-Java references

  Expected: zero hits in production code and test code (V1 Flyway file is immutable history and may keep "ADMIN" in legacy seed inserts — verify those are commented or use the new enum). Common stragglers: test fixtures that hardcoded the enum, MapStruct mappers, JSON test payloads, OpenAPI specs.

  If hits remain, those are call-sites Task 10 missed — fix them before proceeding.

- [ ] **Step 2: Remove the value**

  ```java
  package com.pfplaybackend.api.common.config.security.enums;

  public enum ProviderType {
      GOOGLE,
      TWITTER,
      LOCAL  // Admin local login + virtual users
  }
  ```

- [ ] **Step 3: Compile + test**

  Run: `./gradlew compileJava test --no-daemon`
  Expected: BUILD SUCCESSFUL with all tests passing. (If anything was missed in Step 1, it surfaces here as a compile error.)

- [ ] **Step 4: Commit**

  ```bash
  git add common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java
  git commit -m "feat(iam): remove deprecated ProviderType.ADMIN

  All virtual-member call-sites migrated to LOCAL in earlier tasks.
  ADMIN value no longer referenced anywhere in production or test code.

  Refs: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.1 (6)"
  ```

---

### Task 14: ArchUnit + repository-level verification

**Files:** No source changes; this task is verification-only.

**Background:** The composition refactor reorganizes module boundaries inside `user`. Run ArchUnit to confirm no new cross-module direct imports were introduced and that the hexagonal layout is intact.

- [ ] **Step 1: Read existing ArchUnit rules first**

  `Read` `app/src/test/java/com/pfplaybackend/api/architecture/HexagonalArchitectureTest.java` and any other `*ArchitectureTest.java` to understand what rules exist. Composition introduces a new pattern: `app/.../admin/` services now reach into `userAccountRepository`. If ArchUnit has a rule restricting `app` → `user.adapter.out.persistence` imports (which would force a Port abstraction), this task surfaces it.

- [ ] **Step 2: Run the existing ArchUnit suite**

  Run: `./gradlew :app:test --tests "*ArchitectureTest" --no-daemon`
  Expected: all rules pass.

  Existing rules to keep an eye on:
  - `HexagonalArchitectureTest` — domain/application/adapter layering
  - Any rules covering `user` module isolation
  - Cross-module import direction rules

  **If a rule fails:** the choice is (a) introduce a Port (`UserAccountQueryPort`, etc.) wrapping the repository call so `app` calls through the port instead of the JPA repo directly, or (b) relax the rule if it's overly strict for legitimate cross-module reads. **Option (a) is preferred** for consistency with the rest of the codebase (which already uses `AdminMemberPort`, `PartyCleanupPort`, etc.). If this becomes substantial work, escalate as DONE_WITH_CONCERNS — port introduction is real refactoring beyond Task 14's verification scope.

- [ ] **Step 2: Run any cross-cutting integration tests**

  Run: `./gradlew test --tests "*IntegrationTest" --no-daemon` (filter to integration tests if naming convention allows).
  Expected: all pass. If Testcontainers tests fail to start the MySQL container, document and defer to Task 15's manual smoke test.

- [ ] **Step 3: No commit required (verification only)**

  Exception: if Step 2 required adding a new Port/Adapter to satisfy ArchUnit, that change gets its own commit:

  ```bash
  git commit -m "refactor(iam): introduce UserAccountQueryPort for cross-module reads

  ArchUnit flagged direct app → user.adapter.out.persistence imports
  introduced by the IAM composition refactor. Wrapped in a port to keep
  hexagonal boundaries clean.

  Refs: Task 14 ArchUnit verification"
  ```

  If ArchUnit found a regression that requires a code fix, that's a Task 12 cleanup item — go back and resolve, then re-run.

---

### Task 15: Boot smoke test (Flyway V4 + login + admin gate)

**Files:** No source changes; this task is end-to-end verification.

**Background:** PR 0 established a manual smoke harness for `feature/admin-auth-iam-schema`: spin up MySQL + Redis containers, source `.env`, run `./gradlew :app:bootRun`, verify expected HTTP behavior. This task reuses that harness with the V4 migration in place. Success criterion: app boots cleanly through V1→V2→V3→V4, admin endpoint still 401s, sign-in flow works against the new composition tables (verified via DB inspection rather than full OAuth redirect, which is brittle to test manually).

**Environment note:** This repo runs on Windows 11. Choose **Git Bash / WSL** for the bash commands below, OR use the PowerShell variants documented inline. Do NOT mix shells. PR 0's smoke harness used Git Bash with the JDK 21 toolchain at `/c/Users/Eisen/.jdks/ms-21.0.7`.

- [ ] **Step 0: Pre-flight checks**

  - **Host port collision:** `docker run -p 3306:3306` will fail if a local MySQL is running. Run `Get-Service MySQL* | Where-Object {$_.Status -eq 'Running'}` (PowerShell) or `netstat -ano | findstr :3306` to check. If occupied, either stop the local MySQL OR change the host port to `3307` in Step 1 AND update `SPRING_DATASOURCE_URL` in `.env` to `jdbc:mysql://localhost:3307/pfplay`. Same for Redis port 6379.
  - **`.env` exists:** PR 0 created `.env` (and a temp `.env.dev` that was deleted). Verify `.env` is present and has `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT`.

- [ ] **Step 1: Start ephemeral MySQL + Redis containers**

  ```bash
  docker run --name pfplay-pr1-mysql -d \
    -e MYSQL_ROOT_PASSWORD=root \
    -e MYSQL_DATABASE=pfplay \
    -p 3306:3306 \
    mysql:8.0.30 \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

  docker run --name pfplay-pr1-redis -d -p 6379:6379 redis:7-alpine
  ```

  Wait until MySQL is ready: `docker logs pfplay-pr1-mysql 2>&1 | grep "ready for connections"` (Bash) — should take ~15 seconds.

- [ ] **Step 2: Boot the app (Git Bash variant)**

  ```bash
  export JAVA_HOME=/c/Users/Eisen/.jdks/ms-21.0.7   # JDK 21 — Lombok 1.18.34 incompatible with JDK 25
  set -a && source .env && set +a
  ./gradlew :app:bootRun --quiet &
  ```

  **PowerShell variant** (if not using Git Bash):

  ```powershell
  $env:JAVA_HOME = "C:\Users\Eisen\.jdks\ms-21.0.7"
  Get-Content .env | ForEach-Object {
      if ($_ -match '^([^=#]+)=(.*)$') {
          Set-Item "env:$($matches[1].Trim())" $matches[2].Trim()
      }
  }
  Start-Process -FilePath ".\gradlew.bat" -ArgumentList ":app:bootRun","--quiet" -NoNewWindow
  ```

  Tail the log; expect to see:
  - `Successfully applied 4 migrations to schema 'pfplay'` (Flyway picks up V1→V4)
  - `Started ApiApplication in N seconds`
  - `Tomcat started on port 8080`

  **If V4 migration fails:** read the Flyway error message. Common failure modes:
  - Column type mismatch between V4 SQL and Hibernate's expectation → cross-check entity `@Column` annotations against V4 DDL.
  - FK violation between guest.profile_id and user_profile.id → confirm Task 6 amended V4 to add `fk_guest_profile` (only relevant if Task 6's preferred option was chosen).
  - `lifecycle_status` is from V12 (Avatar BC) — irrelevant to PR 1.

- [ ] **Step 3: Smoke-test the admin gate**

  ```bash
  curl -i http://localhost:8080/api/v1/admin/partyrooms
  # Expect: HTTP/1.1 401 Unauthorized + WWW-Authenticate: Bearer (PR 0 invariant)
  ```

- [ ] **Step 4: Verify schema directly**

  ```bash
  docker exec pfplay-pr1-mysql mysql -uroot -proot pfplay -e \
    "DESCRIBE user_account; DESCRIBE member; DESCRIBE guest;"
  ```

  Acceptance criteria:
  - `user_account` has `email VARCHAR(255)`, `provider_type VARCHAR(16)`, `password_hash VARCHAR(255)`, `last_login_at`, `withdrawn_at`.
  - `member` has `member_id BIGINT AUTO_INCREMENT PK`, `user_account_id BIGINT NOT NULL`, NO `email`/`provider_type` columns.
  - `guest` has `guest_id BIGINT AUTO_INCREMENT PK`, `user_account_id BIGINT NOT NULL`. **Branch by Task 6 option:**
    - If Task 6 chose preferred (V4 amended): `authority_tier`, `profile_id`, `is_profile_updated` columns ARE present.
    - If Task 6 chose alternative (drop fields from GuestData): these columns are ABSENT, and `Grep "authorityTier\\|profileData" GuestData.java` returns zero hits.
  - `flyway_schema_history` has a row with `version='4'` and `success=1`.

  ```bash
  docker exec pfplay-pr1-mysql mysql -uroot -proot pfplay -e \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
  ```

- [ ] **Step 5: Verify the admin user bootstrap ran**

  First, resolve the admin user-id constant. Run:
  ```
  Grep "adminUserId|ADMIN_USER_ID|new UserId\\([0-9]" --glob "**/AdminUserInitializeService.java"
  ```
  Identify the literal Long value used (likely `1L` or similar small fixed id). Substitute that value below.

  Then verify both tables have the bootstrap rows:

  ```bash
  ADMIN_ID=<literal value from grep>
  docker exec pfplay-pr1-mysql mysql -uroot -proot pfplay -e \
    "SELECT user_id, email, provider_type FROM user_account WHERE user_id = $ADMIN_ID;
     SELECT member_id, user_account_id, authority_tier FROM member WHERE user_account_id = $ADMIN_ID;"
  ```

  Expect: one row in each table with the admin's userId. Provider type should be `'GOOGLE'` (admin keeps GOOGLE in PR 1; PR 2 V5 will replace it with `'LOCAL'`).

- [ ] **Step 6: Stop the app + clean up containers**

  ```bash
  # Git Bash: kill the backgrounded bootRun
  pkill -f bootRun
  # OR (if launched via Start-Process in PowerShell)
  # Get-Process java | Where-Object {$_.MainWindowTitle -like "*bootRun*"} | Stop-Process

  docker rm -f pfplay-pr1-mysql pfplay-pr1-redis
  ```

- [ ] **Step 7: Commit any deferred fixes**

  If steps 4–5 surfaced anomalies (unexpected column shape, missing seed data, etc.), iterate: fix → re-boot → re-verify. Each fix gets its own commit. **Only proceed to PR review when steps 1–6 pass cleanly.**

  No commit for this task itself — verification doesn't change source. The commit log already reflects the cumulative work.

---

## Final Checkpoint

After Task 15:
- All Flyway migrations V1→V4 apply cleanly to a fresh database.
- `./gradlew compileJava test` passes (entity + repository + ArchUnit + integration tests).
- App boots; admin endpoint returns 401 for unauthenticated requests.
- `user_account` is a standalone IAM aggregate; `member`/`guest` reference it by `user_account_id` value (no FK).
- `provider_type` is `VARCHAR(16) STRING`; `LOCAL` is the canonical value for admin local login + virtual users; `ADMIN` enum value is removed.
- Lifecycle fields `last_login_at` (updated on every sign-in) and `withdrawn_at` (set by `UserAccount.withdraw()`) are wired.

**Branch state:** `feature/admin-auth-iam-schema` is now PR 0 (7 commits) + PR 1 (~13 commits, one per task). Ready to be opened as a PR or rolled into the larger admin-platform feature branch per the team's PR-batching policy. **Do not open a PR yet** unless explicitly asked — the user's stated workflow is to land PR 0–11 incrementally on this branch and only open the consolidated PR when the milestone-1 cluster is complete.

