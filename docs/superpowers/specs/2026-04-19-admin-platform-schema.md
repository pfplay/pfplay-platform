# PFPlay Admin Platform — Schema Design (§4)

> Companion to `2026-04-19-admin-platform-design.md`. 본 문서는 §4 Schema Design만을 다룬다.
> V4~V12 Flyway 마이그레이션의 DDL, 전환 전략, 리팩토링 범위를 확정한다.

## 4.0 Migration Overview

| V | Context | 내용 | 변경 성격 | 영향 범위 |
|---|---|---|---|---|
| V4 | IAM + User Profile | `user_account` / `member` / `guest` 재구성 (상속→composition), `profileData` 이동, `providerType` VARCHAR 전환 | DROP + CREATE | **대규모** — user 도메인 전반 |
| V5 | Administration | `administrator` 테이블 + 슈퍼어드민 placeholder seed | CREATE + INSERT | 중간 — Admin 초기화 로직 연계 |
| V6 | Party | `partyroom` 상태 enum + `crew_count` / `last_activity_at` / `display_flag` | ALTER + 엔티티 전체 리팩토링 | 대규모 — `isTerminated()` 호출 전반 |
| V7 | Administration | `partyroom_admin_action` 테이블 | CREATE | 낮음 — 신규 모듈 |
| V8 | Party | `crew_penalty_history` / `crew_block_history`에 `punisher_type` 컬럼 추가 | ALTER | 낮음 |
| V9 | Operations | `system_config` 테이블 + `maintenance.*` seed | CREATE + INSERT | 낮음 |
| V10 | Administration | `user_activity_log` 테이블 (월별 파티셔닝) | CREATE | 낮음 |
| V11 | Administration | `partyroom_report` 테이블 | CREATE | 낮음 |
| **V12** 🆕 | **Avatar** | `avatar_body_resource` / `avatar_face_resource`에 `icon_uri`, `lifecycle_status`, 감사 컬럼 추가. `face.obtainable_type` 컬럼 신설. `avatar_icon_resource` 테이블 DROP + 데이터 이전. | ALTER + UPDATE + DROP | 중간 — Avatar 엔티티/레포 이관 동반 |

## 4.1 V4 — IAM Refactor

### 4.1.1 목표

1. `UserAccountData` JPA 상속(`@Inheritance(JOINED)`) 구조 제거
2. `UserAccount` / `Member` / `Guest`를 독립 엔티티로 분리 (composition)
3. `authorityTier`를 부모에서 Member로 이동 (Party 개념으로 귀속)
4. `profileData`를 부모에서 Member로 이동 (Party 개념)
5. `providerType` 저장을 tinyint(ordinal) → VARCHAR(16) 전환 (enum 추가 시 silent shift 방지)
6. `providerType`에 `LOCAL` 값 추가 (어드민 로컬 로그인용), 기존 `ADMIN` 값 제거 (가상 유저도 `LOCAL`로 통합)
7. IAM 수준의 라이프사이클 필드 추가: `last_login_at`, `withdrawn_at`

### 4.1.2 DDL (V4__refactor_user_account_to_iam.sql)

```sql
-- =====================================================
-- V4: IAM Refactor (inheritance → composition)
--
-- Pre-launch, no real users → DROP + CREATE 전략.
-- 기존 user_account/member/guest 테이블 드롭 후 재구성.
-- =====================================================

-- 1. FK / 제약 해제 후 기존 테이블 drop
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS guest;
DROP TABLE IF EXISTS user_account;

SET FOREIGN_KEY_CHECKS = 1;

-- 2. IAM: user_account (standalone)
CREATE TABLE user_account (
    user_id         BIGINT       NOT NULL,
    email           VARCHAR(255) NOT NULL,
    provider_type   VARCHAR(16)  NOT NULL,           -- 'GOOGLE' | 'TWITTER' | 'LOCAL'
    password_hash   VARCHAR(255) NULL,               -- LOCAL일 때만 세팅
    last_login_at   DATETIME     NULL,
    withdrawn_at    DATETIME     NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Party: member (user_account_id 참조, FK 아님)
CREATE TABLE member (
    member_id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_account_id      BIGINT      NOT NULL,              -- 값 참조 (cross-context, no FK)
    authority_tier       ENUM('FM','AM','GT') NOT NULL,
    profile_id           BIGINT UNSIGNED NULL,              -- 같은 컨텍스트 (Party), FK 유지
    is_profile_updated   BIT         NOT NULL DEFAULT 0,
    created_at           DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_user_account (user_account_id),
    CONSTRAINT fk_member_profile FOREIGN KEY (profile_id) REFERENCES user_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Party: guest (user_account_id 참조, FK 아님)
CREATE TABLE guest (
    guest_id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_account_id  BIGINT       NOT NULL,                 -- 값 참조 (cross-context, no FK)
    agent            VARCHAR(255) NULL,
    created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id),
    UNIQUE KEY uk_guest_user_account (user_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 4.1.3 엔티티 리팩토링 범위 (같은 PR)

**`UserAccountData` (신규)** — abstract 제거, 구체 클래스화
- 기존 `abstract class UserAccountData extends BaseEntity`에서 `@Inheritance(JOINED)` 완전 제거
- 새로운 `@Table(name="USER_ACCOUNT")` 엔티티, `@Id` `@Column(name="user_id") Long userId`
- 필드: email, providerType (enum), passwordHash, lastLoginAt, withdrawnAt
- 메서드: `withdraw()` — 익명화 + `UserAccountWithdrawn` 이벤트 발행

**`MemberData`** — 부모 상속 제거, composition 전환
- 기존 `extends UserAccountData` 제거, `extends BaseEntity`
- `memberId` 자체 PK (AUTO_INCREMENT) 또는 Embedded `MemberId` 값 객체
- `userAccountId` 값 참조 (UserAccountId 값 객체 또는 Long)
- `authorityTier` 필드 이관 (부모에서 옮겨옴)
- `profileData` 필드 이관 (부모에서 옮겨옴)
- `isProfileUpdated` 필드 이관
- 팩토리: `Member.createForSocial(UserAccountId, email, providerType)` 등

**`GuestData`** — 부모 상속 제거, composition 전환
- 유사한 방식

**`ProviderType` (enum)** — ADMIN 제거, LOCAL 추가
```java
public enum ProviderType {
    GOOGLE,
    TWITTER,
    LOCAL;  // 어드민 로컬 로그인 + 가상 유저 생성 모두 이 값 사용
}
```

**`MemberRepository` / `GuestRepository`**:
- 기존 메서드들 (`findByEmail`, `countByProviderType`) — email/providerType이 UserAccount로 이동했으므로 해당 쿼리는 UserAccount 경유로 재작성
- 신규: `findByUserAccountId(UserAccountId)`

**`UserAccountRepository` (신규)**:
- `findByEmail(String)`, `findByEmailAndProviderType(String, ProviderType)`
- `existsByEmail(String)`

### 4.1.4 기존 코드에 미치는 영향 (Breaking changes)

다음 코드가 영향받으므로 같은 PR에서 수정:

| 영향 | 조치 |
|---|---|
| `MemberData.createWithFixedUserId(userId, email, providerType)` 호출부 (AdminUserInitializeService, TemporaryUserInitializeService, AdminDemoService 등) | UserAccount 먼저 생성 후 Member 생성으로 분리 |
| `MemberData.getEmail()` / `getProviderType()` 호출부 | UserAccount 조회로 변경 (Repository join) |
| JWT Converter에서 authorityTier 클레임 추출 | Member 조회 경유 |
| `user_type` discriminator를 쿼리에서 사용하는 곳 | 전부 제거 (JPA 인헤리턴스 자체가 사라짐) |

### 4.1.5 Flyway 적용 시 주의

- V4는 DROP + CREATE이므로 **기존 V1 schema의 MEMBER/USER_ACCOUNT/GUEST 테이블 데이터는 모두 사라진다**. Pre-launch라 허용.
- V3에서 seed된 avatar 리소스 테이블(`avatar_body_resource`, `avatar_face_resource`, `avatar_icon_resource`)은 영향 없음 (별 테이블).
- Foreign key dependency: `user_profile.user_id` 참조 주의 — user_profile은 그대로 유지되지만 member의 profile_id FK로 참조 관계 재구성.

## 4.2 V5 — Administrator + Super Admin Seed

### 4.2.1 DDL (V5__create_administrator.sql)

```sql
-- =====================================================
-- V5: Administration context — Administrator aggregate
--
-- Super admin singleton enforced by functional unique index.
-- Seed row inserted with placeholder password; ApplicationReadyEvent
-- hook replaces with bcrypt(env.ADMIN_SEED_PASSWORD) on first boot.
-- =====================================================

CREATE TABLE administrator (
    administrator_id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_account_id               BIGINT      NOT NULL,              -- 값 참조 (cross-context, no FK)
    role                          VARCHAR(32) NOT NULL,              -- 'SUPER_ADMIN' | 'ADMIN'
    granted_by_administrator_id   BIGINT      NULL,                   -- self-ref (same context, FK OK)
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

-- SUPER_ADMIN 유일성 강제
CREATE UNIQUE INDEX uk_administrator_super_admin
    ON administrator ((CASE WHEN role = 'SUPER_ADMIN' THEN 1 ELSE NULL END));

-- 슈퍼어드민 placeholder 시딩 (app이 env 기반 비번으로 교체)
-- user_account도 placeholder로 먼저 생성
INSERT INTO user_account (user_id, email, provider_type, password_hash, created_at, updated_at)
VALUES (
    1,                                        -- super admin의 fixed user_id
    '__SUPER_ADMIN_PLACEHOLDER_EMAIL__',       -- ApplicationReadyEvent가 env.ADMIN_SEED_EMAIL로 교체
    'LOCAL',
    '__SUPER_ADMIN_PLACEHOLDER_HASH__',        -- ApplicationReadyEvent가 bcrypt(env.ADMIN_SEED_PASSWORD)로 교체
    NOW(),
    NOW()
);

INSERT INTO administrator (administrator_id, user_account_id, role, granted_by_administrator_id, granted_at, created_at, updated_at)
VALUES (
    1,                                        -- super admin의 fixed administrator_id
    1,                                        -- 위 user_account 참조
    'SUPER_ADMIN',
    NULL,                                     -- 슈퍼어드민은 grantedBy 없음
    NOW(),
    NOW(),
    NOW()
);
```

### 4.2.2 Placeholder 교체 로직 (ApplicationReadyEventListener 확장)

```java
@EventListener(ApplicationReadyEvent.class)
public void onApplicationEvent() {
    // ... 기존 Party 시드 로직 ...

    // 슈퍼어드민 placeholder 교체
    superAdminSeedService.finalizeSuperAdminCredentials();
}
```

`SuperAdminSeedService.finalizeSuperAdminCredentials()`:
1. `user_account` 중 `email='__SUPER_ADMIN_PLACEHOLDER_EMAIL__'` 찾기
2. 존재하면:
   - env `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD` 읽기
   - 둘 중 하나라도 미설정 시 `log.error` + 애플리케이션 정지 (운영상 필수)
   - bcrypt(ADMIN_SEED_PASSWORD, cost=12)
   - UPDATE user_account SET email=?, password_hash=? WHERE user_id=1
   - env variables 참조 null화 (메모리 노출 축소)
3. 존재하지 않으면 (이미 교체됨): no-op
4. Idempotent 보장 — 재부팅 시 placeholder가 없으면 건너뜀

### 4.2.3 기존 `ApplicationReadyEventListener.addAdminUser()` 제거

기존 "AdminUserInitializeService.addAdminUser()" 호출은 V5 도입 시 **제거**. V5의 INSERT가 이 역할을 대신함.

**주의**: 이 때 `addAdminUser()`가 생성하던 `MemberData`는 어떻게 되는가?
- 기존 로직: admin placeholder user가 Member로도 존재 (avatar 등 프로필 가짐, main stage host로 사용)
- 신 로직: `administrator`는 `user_account` 레벨. Member는 별개.
- **Main stage host**로 쓰려면 admin 계정에 대응하는 Member가 필요
- **해결**: `V5`에 Member 레코드 추가 INSERT (auto-increment) + `ApplicationReadyEvent`의 `initializeMainStage(adminMemberId)`가 그 Member 참조

보완 DDL (V5에 추가):
```sql
-- 슈퍼어드민의 Party 측 Member 레코드 (main stage host로 쓰기 위함)
INSERT INTO member (member_id, user_account_id, authority_tier, is_profile_updated, created_at, updated_at)
VALUES (1, 1, 'FM', 0, NOW(), NOW());
```

### 4.2.4 Architecture review 반영

- ✅ SUPER_ADMIN singleton: functional unique index로 DB 레벨 enforce
- ✅ VARCHAR(32) role: MySQL ENUM 대신 (RBAC 확장 시 ALTER TABLE 회피)
- ✅ env password 핸들링: 1회 읽기 → bcrypt → reference 폐기
- ✅ ApplicationReadyEventListener race 해소: 기존 addAdminUser() 제거, V5 INSERT가 대신

## 4.3 V6 — Partyroom 상태 모델 + 전체 리팩토링

### 4.3.1 DDL (V6__evolve_partyroom_state.sql)

```sql
-- =====================================================
-- V6: Party context — Partyroom 상태 모델 진화
--
-- - is_terminated BOOLEAN → status ENUM (3-상태)
-- - crew_count, last_activity_at denormalized 카운터
-- - display_flag (Operations 관점이지만 물리적으론 Party 테이블)
-- =====================================================

-- 새 컬럼 먼저 추가
ALTER TABLE partyroom
    ADD COLUMN status ENUM('ACTIVE','SUSPENDED','TERMINATED') NOT NULL DEFAULT 'ACTIVE' AFTER is_terminated,
    ADD COLUMN crew_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_activity_at DATETIME NULL,
    ADD COLUMN display_flag ENUM('NORMAL','FEATURED','HIDDEN') NOT NULL DEFAULT 'NORMAL';

-- 기존 is_terminated → status 데이터 이관
UPDATE partyroom SET status = 'TERMINATED' WHERE is_terminated = 1;
UPDATE partyroom SET status = 'ACTIVE' WHERE is_terminated = 0;

-- crew_count 초기 계산 (활성 crew만)
UPDATE partyroom p
SET crew_count = (
    SELECT COUNT(*) FROM crew c
    WHERE c.partyroom_id = p.partyroom_id AND c.is_active = 1
);

-- last_activity_at 초기값: partyroom.updated_at or created_at
UPDATE partyroom SET last_activity_at = COALESCE(updated_at, created_at) WHERE last_activity_at IS NULL;

-- 기존 컬럼 제거
ALTER TABLE partyroom DROP COLUMN is_terminated;

-- 인덱스 추가 (목록 쿼리 최적화)
CREATE INDEX idx_partyroom_status_activity ON partyroom (status, last_activity_at DESC);
CREATE INDEX idx_partyroom_display_flag ON partyroom (display_flag);
```

### 4.3.2 엔티티/서비스 리팩토링 범위

**`PartyroomData`**:
- `boolean isTerminated` 필드 제거
- `PartyroomStatus status` 필드 추가 (enum: ACTIVE/SUSPENDED/TERMINATED)
- `int crewCount`, `LocalDateTime lastActivityAt`, `DisplayFlag displayFlag` 추가
- 메서드:
  - `isActive()` → `status == ACTIVE`
  - `isSuspended()` → `status == SUSPENDED`
  - `isTerminated()` → `status == TERMINATED` (메서드 유지, 의미는 동일)
  - `suspend()`, `restore()`, `terminate()` — 상태 전이 (불변식 검증)
  - `incrementCrewCount()`, `decrementCrewCount()` — denormalized 카운터 유지
  - `updateLastActivity(LocalDateTime)` — 최근 활동 시각 업데이트

**`PartyroomEntrySpecification`** (기존): `isSuspended()` 상태 추가 거부 로직 추가

**도메인 이벤트 리스너**:
- `CrewAccessedEvent(ENTER/EXIT)` 수신 → `partyroom.crew_count` 증감 + `lastActivityAt` 갱신
- `PlaybackChangedEvent` 수신 → `lastActivityAt` 갱신 (추후 정의)

**`@PreAuthorize` / 기존 권한 체크**:
- `isTerminated()` 체크하던 코드 전수 조사 → `status == ACTIVE` 또는 상태별 분기로 재작성
- 예상 영향 파일: `PartyroomCommandService`, `PartyroomAccessCommandService`, `PlaybackCommandService`, `PartyroomQueryService`, 기타 Partyroom 참조 서비스

**`display_flag` 쓰기 권한**:
- Party 서비스에서 `displayFlag` setter 호출 **금지** (package-private 정도로 visibility 제한)
- Administration 서비스만 변경 (별도 command service 경유)

### 4.3.3 카운터 drift 방지

denormalized `crew_count`가 실제 crew 테이블과 달라지는 drift 방지:
- 크루 enter/exit 이벤트로 업데이트하되
- 주기적 검증 배치 (예: 매일 새벽) — drift 발견 시 실제 값으로 재계산
- 기록 남기기 (로그 + metric)

## 4.4 V7 — partyroom_admin_action

### 4.4.1 DDL (V7__create_partyroom_admin_action.sql)

```sql
-- =====================================================
-- V7: Administration context — AdminAction aggregate
--
-- 어드민의 시스템 액션 감사 로그. Append-only.
-- 교차 컨텍스트 참조(partyroom_id, affected_crew_id)는 FK 없이 값 저장.
-- =====================================================

CREATE TABLE partyroom_admin_action (
    action_id          BIGINT       NOT NULL AUTO_INCREMENT,
    administrator_id   BIGINT       NOT NULL,                  -- same context, FK OK
    action_type        VARCHAR(32)  NOT NULL,                  -- SUSPEND_PARTYROOM | RESTORE_PARTYROOM
                                                               -- | TERMINATE_PARTYROOM | SET_FEATURED
                                                               -- | SET_HIDDEN | SET_NORMAL
                                                               -- | PENALIZE_CREW | UPDATE_PARTYROOM_META
    target_type        VARCHAR(16)  NOT NULL,                  -- 'PARTYROOM' | 'CREW' | 'MEMBER'
    target_id          BIGINT       NOT NULL,                  -- 대상 ID (loose ref)
    partyroom_id       BIGINT       NULL,                      -- 룸과 관련된 액션인 경우 (denormalized for query)
    reason             TEXT         NULL,                      -- 어드민이 적은 사유
    metadata           JSON         NULL,                      -- action_type별 추가 정보
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

### 4.4.2 action_type 카탈로그

| action_type | target_type | target_id | metadata 예시 | trigger |
|---|---|---|---|---|
| `SUSPEND_PARTYROOM` | PARTYROOM | partyroomId | `{"reason_category": "...}` | `POST /admin/partyrooms/{id}/suspend` |
| `RESTORE_PARTYROOM` | PARTYROOM | partyroomId | — | `POST /admin/partyrooms/{id}/restore` |
| `TERMINATE_PARTYROOM` | PARTYROOM | partyroomId | — | `POST /admin/partyrooms/{id}/terminate` |
| `SET_FEATURED` | PARTYROOM | partyroomId | — | `PATCH /admin/partyrooms/{id}/display-flag` |
| `SET_HIDDEN` | PARTYROOM | partyroomId | — | `PATCH /admin/partyrooms/{id}/display-flag` |
| `SET_NORMAL` | PARTYROOM | partyroomId | — | `PATCH /admin/partyrooms/{id}/display-flag` |
| `PENALIZE_CREW` | CREW | crewId | `{"penalty_type": "CHAT_BAN_30_SECONDS", "partyroom_id": ...}` | 기존 CrewPenaltyCommandController (어드민 경로) |
| `UPDATE_PARTYROOM_META` | PARTYROOM | partyroomId | `{"field": "title", "old": "...", "new": "..."}` | `PATCH /admin/partyrooms/{id}` |
| `CHANGE_MEMBER_TIER` | MEMBER | memberId | `{"old_tier": "AM", "new_tier": "FM"}` | `PATCH /admin/members/{id}/tier` |
| `WITHDRAW_MEMBER` | MEMBER | memberId | — | `POST /admin/members/{id}/withdraw` |

## 4.5 V8 — Penalty History punisher_type

### 4.5.1 DDL (V8__add_punisher_type_to_penalty_history.sql)

```sql
-- =====================================================
-- V8: Party context — penalty/block history에 punisher 유형 추가
--
-- 어드민이 부과한 페널티를 구분. 어드민 정체(id)는 partyroom_admin_action에 별도 기록.
-- =====================================================

ALTER TABLE crew_penalty_history
    ADD COLUMN punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW' AFTER punisher_crew_id;

ALTER TABLE crew_block_history
    ADD COLUMN punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW' AFTER blocker_crew_id;
```

### 4.5.2 Admin 페널티 경로 — correlation

어드민이 페널티 부과 시:
1. 기존 `CrewPenaltyCommandController` (혹은 별 어드민 엔드포인트) 호출
2. Party 서비스가 `crew_penalty_history` INSERT with `punisher_type='ADMIN'`, `punisher_crew_id=...`(어드민의 crew 레코드)
3. Administration 서비스가 **같은 트랜잭션 범위 내 or 이벤트 기반으로** `partyroom_admin_action` INSERT with `action_type='PENALIZE_CREW'`, `target_id=crewId`, `metadata={"penalty_type": "...", "crew_penalty_history_id": ...}`

조회 시 correlation:
- "이 페널티를 누가 부과했나?" → `crew_penalty_history` 조회 후 `partyroom_admin_action.metadata.crew_penalty_history_id` 매칭
- 혹은 timestamp + crew_id 기반 range join (correlation_id 컬럼 없이)

**`metadata.crew_penalty_history_id` 채택 권장** — correlation id 역할.

## 4.6 V9 — system_config

### 4.6.1 DDL (V9__create_system_config.sql)

```sql
-- =====================================================
-- V9: Operations context — SystemConfig (key-value 범용 저장소)
--
-- 유지보수 모드 + 향후 feature flag 수용.
-- =====================================================

CREATE TABLE system_config (
    config_key                        VARCHAR(64)  NOT NULL,
    config_value                      TEXT         NOT NULL,
    description                       VARCHAR(255) NULL,
    updated_by_administrator_id       BIGINT       NULL,             -- loose ref, no FK
    updated_at                        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 유지보수 모드 기본 설정
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('maintenance.enabled', 'false', '유지보수 모드 활성 여부 (true일 때 일반 API 503)'),
    ('maintenance.message', '시스템 점검 중입니다. 잠시 후 다시 시도해주세요.', '유지보수 안내 메시지');
```

### 4.6.2 애플리케이션 연계

- Spring Filter: `MaintenanceModeFilter`
  - 매 요청 시 `SystemConfigCache.get("maintenance.enabled")` 조회
  - `true`인 경우 `/api/v1/admin/**` 제외 503 응답
  - 캐시 TTL 30~60초로 DB 부하 최소화
- Admin endpoint: `PATCH /api/v1/admin/system/config/maintenance` — 토글

## 4.7 V10 — user_activity_log

### 4.7.1 DDL (V10__create_user_activity_log.sql)

```sql
-- =====================================================
-- V10: Administration context — UserActivityLog (감사/audit timeline)
--
-- Append-only. 월별 파티셔닝으로 조회 성능 + 아카이브 용이성 확보.
-- =====================================================

CREATE TABLE user_activity_log (
    log_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_account_id   BIGINT       NOT NULL,                  -- loose ref (cross-context, no FK)
    event_type        VARCHAR(64)  NOT NULL,                  -- SIGNED_IN | PARTYROOM_ENTERED 등
    partyroom_id      BIGINT       NULL,                      -- loose ref, nullable
    metadata          JSON         NULL,                      -- event별 추가 데이터
    occurred_at       DATETIME     NOT NULL,
    PRIMARY KEY (log_id, occurred_at),                        -- 파티션 키 포함 PK
    INDEX idx_ual_user_time (user_account_id, occurred_at DESC),
    INDEX idx_ual_event_time (event_type, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE (TO_DAYS(occurred_at)) (
    -- 초기 파티션: 2026년 4월~12월 분
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
```

### 4.7.2 event_type 카탈로그 (MVP)

| event_type | metadata 키 (예시) | 기록 시점 |
|---|---|---|
| `SIGNED_UP` | `{"provider": "GOOGLE"}` | UserAccount 생성 |
| `SIGNED_IN` | `{"provider": "GOOGLE" / "LOCAL"}` | 로그인 성공 |
| `WITHDREW` | — | 탈퇴 처리 완료 |
| `PROFILE_UPDATED` | `{"fields": ["nickname", "avatar_body"]}` | Member 프로필 변경 |
| `TIER_CHANGED` | `{"old": "AM", "new": "FM", "by_administrator_id": 123}` | 티어 변경 (어드민/지갑 연결) |
| `PARTYROOM_CREATED` | `{"stage_type": "GENERAL"}` | 파티룸 생성 |
| `PARTYROOM_ENTERED` | `{"stage_type": "MAIN"}` | 크루 입장 |
| `PARTYROOM_EXITED` | `{"duration_sec": 1200}` | 크루 퇴장 |
| `PENALIZED_IN_PARTYROOM` | `{"penalty_type": "CHAT_BAN_30_SECONDS", "by": "CREW"/"ADMIN"}` | 페널티 받음 |
| `ADMIN_ACTED_ON` | `{"action_type": "TIER_CHANGED", "by_administrator_id": ...}` | 어드민 액션 대상이 됨 |

### 4.7.3 기록 방식

`ApplicationEventPublisher` 기반 도메인 이벤트 → `UserActivityLogListener`가 구독:

```java
@Component
class UserActivityLogListener {
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Async
    public void on(UserAccountSignedIn e) { 
        logger.log(e.userAccountId, "SIGNED_IN", null, Map.of("provider", e.provider.name()));
    }
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Async
    public void on(CrewAccessedEvent e) {
        if (e.accessType == ENTER) logger.log(e.userAccountId, "PARTYROOM_ENTERED", e.partyroomId, ...);
        else logger.log(e.userAccountId, "PARTYROOM_EXITED", e.partyroomId, ...);
    }
    // ...
}
```

- `@TransactionalEventListener(AFTER_COMMIT)` — 비즈니스 트랜잭션 커밋 후 실행 (로그 실패 ≠ 비즈니스 실패)
- `@Async` — 핫패스 비동기화
- 드롭-가능 허용 (audit-성, 유실 수용)

### 4.7.4 파티션 관리 배치

- 매월 말 새 파티션 생성 (미리 2~3개월 앞당겨)
- 180일 이상 파티션은 아카이브(별 storage로 dump) 후 DROP — 운영 중 결정

## 4.8 V11 — partyroom_report

### 4.8.1 DDL (V11__create_partyroom_report.sql)

```sql
-- =====================================================
-- V11: Administration context — PartyroomReport
--
-- 유저가 파티룸을 신고, 어드민이 검토.
-- =====================================================

CREATE TABLE partyroom_report (
    report_id                      BIGINT       NOT NULL AUTO_INCREMENT,
    partyroom_id                   BIGINT       NOT NULL,               -- loose ref (cross-context, no FK)
    reporter_user_account_id       BIGINT       NOT NULL,               -- loose ref (cross-context, no FK)
    category                       ENUM('INAPPROPRIATE_CONTENT','HARASSMENT','SPAM','COPYRIGHT','OTHER') NOT NULL,
    description                    TEXT         NULL,                    -- 신고자 추가 설명
    status                         ENUM('PENDING','REVIEWING','RESOLVED','DISMISSED') NOT NULL DEFAULT 'PENDING',
    reviewed_by_administrator_id   BIGINT       NULL,                    -- same context, FK OK
    resolution_note                TEXT         NULL,                    -- 어드민 처리 메모
    created_at                     DATETIME     NOT NULL,
    resolved_at                    DATETIME     NULL,
    PRIMARY KEY (report_id),
    CONSTRAINT fk_pr_reviewed_by
        FOREIGN KEY (reviewed_by_administrator_id)
        REFERENCES administrator(administrator_id),
    INDEX idx_pr_status_created (status, created_at DESC),
    INDEX idx_pr_partyroom (partyroom_id, created_at DESC),
    INDEX idx_pr_reporter (reporter_user_account_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 4.8.2 상태 전이

```
PENDING  ──(어드민이 검토 시작)──►  REVIEWING
   │
   │  (어드민이 무효 판단)
   └────────────────────────────►  DISMISSED   [resolved_at 설정]
                                     
REVIEWING ──(해결)──► RESOLVED     [resolved_at 설정, resolution_note 기록]
REVIEWING ──(보류)──► PENDING      [가능 but 드문 케이스]
```

### 4.8.3 "주의 필요" 뷰 계산

어드민 파티룸 목록의 "주의 필요" 뷰:
- `partyroom`에 대해 `partyroom_report` join (status IN ('PENDING', 'REVIEWING'))
- OR `crew_penalty_history` 최근 24h 이내 (`partyroom_id` 기준 집계)

## 4.9 컨텍스트별 FK 정책 요약

| 테이블 | Context | 외부 참조 컬럼 | FK 여부 |
|---|---|---|---|
| `user_account` | IAM | — | — |
| `member` | User Profile | `user_account_id` | ❌ no FK, UNIQUE |
| `member` | User Profile | `profile_id` | ✅ FK (`user_profile`, same context) |
| `guest` | User Profile | `user_account_id` | ❌ no FK, UNIQUE |
| `partyroom` | Party | `host_id` (user 참조) | ❌ 기존 관행 유지 (Party 내 usage) |
| `administrator` | Administration | `user_account_id` | ❌ no FK, UNIQUE |
| `administrator` | Administration | `granted_by_administrator_id` | ✅ FK (self-ref, same context) |
| `partyroom_admin_action` | Administration | `administrator_id` | ✅ FK (same context) |
| `partyroom_admin_action` | Administration | `partyroom_id`, `target_id` | ❌ loose ref |
| `crew_penalty_history` | Party | (기존 FK 유지) | (기존) |
| `partyroom_report` | Administration | `reviewed_by_administrator_id` | ✅ FK (same context) |
| `partyroom_report` | Administration | `partyroom_id`, `reporter_user_account_id` | ❌ loose ref |
| `user_activity_log` | Administration | `user_account_id`, `partyroom_id` | ❌ loose ref |
| `system_config` | Operations | `updated_by_administrator_id` | ❌ loose ref |
| `avatar_body_resource` | Avatar | `created_by`, `updated_by` (administrator 참조) | ❌ loose ref |
| `avatar_face_resource` | Avatar | `created_by`, `updated_by` (administrator 참조) | ❌ loose ref |
| `member.avatar_setting.avatar_*_uri` | User Profile → Avatar | Avatar 리소스 URI | ❌ URI 값만 참조 (ID FK 금지) |

**원칙 재확인**:
- 같은 컨텍스트 내 FK는 OK
- 컨텍스트 경계 넘는 참조는 값만 저장 (UNIQUE 제약으로 중복 방지는 가능)
- Shared Kernel (UserId, PartyroomId 같은 값 객체)은 Common 모듈에서 정의하여 전 컨텍스트가 값으로만 사용

## 4.10 Migration 순서 의존성

```
V4 (IAM, User Profile 리팩)
  ↓
V5 (Administrator — user_account 참조)
  ↓
V6 (Partyroom 상태 진화 — 독립적이지만 다음 PR 후 진행)
  ↓
V7 (partyroom_admin_action — administrator FK 있음)
  ↓
V8 (penalty history augmentation — 독립적)
  ↓
V9 (system_config — 독립적)
  ↓
V10 (user_activity_log — user_account 참조, Administration)
  ↓
V11 (partyroom_report — administrator FK 있음)
  ↓
V12 (Avatar BC 재구성 — 기존 V3 시드 위에 진행, 다른 V와 독립)
```

V6/V8/V9는 상호 독립적이므로 PR 병렬 가능. V12(Avatar)는 다른 마이그레이션과 완전 독립이지만 Administration의 `admin_action` 테이블(V7)에 의존하는 이벤트 리스너가 있어 배치상 V7 이후 PR에 배치.

## 4.11 V12 — Avatar BC Restructure 🆕

### 4.11.1 목표

1. `avatar_icon_resource` 별 테이블 제거 → body/face의 `icon_uri` 필드로 흡수 (1:1 관계를 스키마가 직접 표현)
2. `PairType` enum(ordinal 저장 footgun) 제거 — 별 테이블이 사라지면서 discriminator 소멸
3. `lifecycle_status` (`DRAFT` | `PUBLISHED` | `RETIRED`) 도입 — 과금 영역 대비 soft-lifecycle (삭제 불가)
4. face에 `obtainable_type` 컬럼 신설 (`BASIC` 고정) — 향후 과금 확장 대비 선행 컬럼
5. 감사 컬럼 추가 (`created_at/by`, `updated_at/by` — 어드민 CRUD 이력)

### 4.11.2 Pre-condition

- V3 이미 실행됨 (`avatar_body_resource` 15행 + `avatar_face_resource` 1행 + `avatar_icon_resource` 5행 + UNIQUE 제약 존재)
- `obtainable_type` 컬럼은 이미 VARCHAR(`@Enumerated(STRING)`) 저장 — ordinal 이슈 없음
- `avatar_icon_resource.pair_type`은 tinyint(ordinal). 이 테이블 DROP으로 해당 footgun도 함께 해소

### 4.11.3 DDL (V12__avatar_bc_restructure.sql)

```sql
-- =====================================================
-- V12: Avatar BC Restructure
--
-- Avatar BC 신설에 따른 리소스 테이블 재구성.
-- 핵심 변경:
--   1. icon_uri를 body/face의 필드로 흡수 (avatar_icon_resource 제거)
--   2. lifecycle_status (DRAFT/PUBLISHED/RETIRED) 도입 — 과금 대비 soft-lifecycle
--   3. face에도 obtainable_type 컬럼 신설 (BASIC 고정, 추후 PURCHASE 확장 대비)
--   4. 감사 컬럼 추가 (created/updated by administrator_id)
-- =====================================================

-- Step 1. body: icon_uri, lifecycle, 감사 컬럼 추가
ALTER TABLE avatar_body_resource
    ADD COLUMN icon_uri         VARCHAR(500) NULL        AFTER resource_uri,
    ADD COLUMN lifecycle_status VARCHAR(16)  NOT NULL
        DEFAULT 'PUBLISHED'                               AFTER is_default_setting,
    ADD COLUMN created_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP                         AFTER combine_positiony,
    ADD COLUMN created_by       BIGINT       NULL        AFTER created_at,
        -- administrator_id. NULL = 시스템 시드 (V3).
        -- cross-BC loose ref (no FK).
    ADD COLUMN updated_at       DATETIME     NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP                       AFTER created_by,
    ADD COLUMN updated_by       BIGINT       NULL        AFTER updated_at;

ALTER TABLE avatar_body_resource
    ADD CONSTRAINT chk_body_lifecycle
        CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED'));

-- Step 2. face: icon_uri, lifecycle, obtainable_type, 감사 컬럼 추가
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
        -- BASIC 고정. 추후 ENUM 확장 시 이 CHECK를 ALTER.
        -- (MySQL 8.0.16+ CHECK는 실제 enforce됨.)

-- Step 3. 기존 avatar_icon_resource 데이터를 부모의 icon_uri로 이전
--   네이밍 규약:
--     body icon:  name LIKE 'ava_icon_body_%'  pairs with  body whose name is 'ava_' || SUBSTRING(i.name FROM 10)
--     face icon:  name LIKE 'ava_icon_face_%'  pairs with  face whose name is 'ava_' || SUBSTRING(i.name FROM 10)
--   pair_type 컬럼(tinyint ordinal)에 의존하지 않고 이름 접두어로 판별 — 더 방어적.
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

-- Step 4. avatar_icon_resource DROP (PairType enum도 동반 삭제 PR)
DROP TABLE avatar_icon_resource;
```

### 4.11.4 불변식 체크리스트

| 불변식 | Enforce | 비고 |
|---|---|---|
| body/face `name` 전역 UNIQUE | V3에서 이미 추가 (`uk_avatar_body_name`, `uk_avatar_face_name`) | 유지 |
| lifecycle 값 범위 | `CHECK` 제약 | V12 추가 |
| lifecycle 전이 단방향 (`DRAFT → PUBLISHED → RETIRED`) | **애플리케이션 레이어** (aggregate method) | DB trigger는 유지보수 부담 이유로 배제 |
| `is_default_setting=true` → `obtainable_type=BASIC AND lifecycle=PUBLISHED` | 애플리케이션 레이어 | |
| `obtainable_type=BASIC` → `obtainable_score=0` | 애플리케이션 레이어 | |
| `icon_uri` NULL 허용 | 컬럼 NULL | 업로드 실패 후 재시도 시나리오 대응. 장기적으로 NOT NULL 타이트화 검토 |

### 4.11.5 주의 사항

- MySQL은 DDL이 **암시적 커밋**을 유발하므로 Step 1/2/3/4 사이 트랜잭션 경계가 나뉜다. Step 2 실패 시 Step 1은 커밋된 상태로 남는다. **pre-launch 단계에서는 허용**. 실패 시 V13 보정 마이그레이션으로 대응.
- `lifecycle_status DEFAULT 'PUBLISHED'`는 **V12 이전 존재하던 15+1행을 PUBLISHED로 올려야** 하기 때문. 신규 INSERT(어드민 CRUD) 시에는 DB default에 의존하지 않고 `AvatarBodyResource.draft(...)` 팩토리가 `lifecycle_status='DRAFT'`를 명시적으로 세팅해 INSERT한다. JPA 엔티티 기본값 설정으로 강제 (§3.3.5 "엔티티 기본값 주의" 참고).
- 기존 `AvatarIconResourceData` JPA 엔티티 + `AvatarIconResourceRepository` + `PairType` enum + `AvatarResourceQueryService.findByNameAndPairType(...)` 호출부 삭제는 V12 적용과 **반드시 동일 PR**에 묶인다 (PR 10). 분리 시 JPA 부트 실패.
- V3 시드 주석 "`pair_type: BODY=0, FACE=1 (ORDINAL mapping of PairType enum)`"은 V12 실행 후 무효한 사실이 되지만, V3 SQL은 이미 flyway_schema_history에 기록된 불변 파일이라 수정하지 않는다 (Flyway 원칙).
- `lifecycle_status` 전이 단방향 보장을 **DB trigger로 추가 강제**할지는 REVISIT-LATER 항목. 현재는 aggregate method만 보호. 과금 확장 시점에 재검토.
- `lifecycle_status DEFAULT 'PUBLISHED'`는 V12 이후 역할이 끝남 (신규 INSERT는 aggregate가 `DRAFT` 명시). **REVISIT-LATER**: V12가 안정 후 후속 V13에서 `ALTER TABLE ... ALTER COLUMN lifecycle_status DROP DEFAULT`로 제거하면 aggregate 우회 INSERT 실수를 조기 차단 가능. 낮은 우선순위.

### 4.11.6 V12 이후 스키마 모습 (요약)

```sql
avatar_body_resource:
    id, name (UNIQUE), resource_uri,
    icon_uri NULL,
    obtainable_type VARCHAR(16),      -- BASIC | DJ_PNT | (future: PURCHASE, EVENT)
    obtainable_score INT,
    is_combinable BOOL,
    is_default_setting BOOL,
    combine_positionx/y INT,
    lifecycle_status VARCHAR(16),     -- DRAFT | PUBLISHED | RETIRED
    created_at/by, updated_at/by

avatar_face_resource:
    id, name (UNIQUE), resource_uri,
    icon_uri NULL,
    obtainable_type VARCHAR(16) DEFAULT 'BASIC',
    lifecycle_status VARCHAR(16),
    created_at/by, updated_at/by

-- avatar_icon_resource: (DROP됨)
```

---

**다음 문서**: `2026-04-19-admin-platform-security.md` (§5)
