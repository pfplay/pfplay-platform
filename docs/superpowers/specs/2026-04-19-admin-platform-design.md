# PFPlay Admin Platform Design

> **Status**: Draft (supersedes earlier `admin-auth-design` scope)
> **Scope**: 어드민 플랫폼 전체 — 인증, 권한, 유저/파티룸/모더레이션/공지/시스템 운영
> **Audience**: 백엔드 엔지니어, 프론트엔드 엔지니어, 운영/CS 팀

## 0. TL;DR

PFPlay는 파티룸 기반 음악 스트리밍 서비스다. 본 문서는 `pfplay-admin` 프런트엔드와 이를 뒷받침할 백엔드 어드민 플랫폼의 중장기 설계를 정의한다. 서비스는 pre-launch 상태이며, 이 설계를 기점으로 11개의 Flyway 마이그레이션(V4~V11)과 다수의 PR로 구현된다.

핵심 설계 결정:
- **F2 (Composition)** 바운디드 컨텍스트 분리: IAM / Party / Administration / Operations
- **Cross-context FK 금지** — 무결성은 애플리케이션 레이어 + 도메인 이벤트로 보장
- **슈퍼어드민 1명 + 일반 어드민 N** (확장 가능한 RBAC 기반)
- **어드민 로컬 로그인 + 크로스-서브도메인 쿠키** (admin.pfplay.xyz ↔ pfplay.xyz 간 세션 공유)
- **Flyway 중심 스키마 진화** — Java initializer는 도메인 로직 동반 시드에만 사용
- **친구/DM 같은 미래 기능에 대한 스키마 과설계 금지** — YAGNI 준수

## 1. Context & Goals

### 1.1 서비스 개요

PFPlay는 파티룸(partyroom)이라는 공간에서 여러 크루(crew)가 모여 DJ가 재생하는 음악을 함께 듣고 리액션/채팅하는 음악 스트리밍 서비스다. 메인 스테이지(`stage_type=MAIN`) 1개와 여러 일반 파티룸(`GENERAL`)으로 구성된다.

### 1.2 어드민이 해결하는 문제

- **서비스 정상성 유지**: 문제 파티룸/유저 모니터링, 페널티 부과, 강제 종료
- **콘텐츠 품질**: 신고 접수/처리, 부적절 콘텐츠 차단
- **운영 커뮤니케이션**: 전체 공지, 이벤트 공지, 푸시 알림
- **유저 지원**: 문의 응답, 유저 상태 확인/조정
- **데이터 기반 의사결정**: DAU, 룸 수, 재생 수 등 지표 확인
- **시스템 운영**: 유지보수 모드, 설정 변경

### 1.3 비즈니스 목표

- 어드민 한 명이 수백 개 파티룸을 효율적으로 모니터링 가능
- 신고 접수 후 빠른 처리가 가능한 UX
- 규모 성장에도 어드민 인력을 선형적으로 늘리지 않아도 되는 구조
- 향후 권한 세분화(유저 전용/모더레이션 전용 어드민) 확장 가능
- 감사(audit) 추적으로 분쟁/오남용 대응

### 1.4 현 시점 제약

- **Pre-launch**: 실유저 없음, 데이터 손실 없이 스키마 리셋 가능
- **1인 개발**: 대형 리팩토링은 감내 가능하나 리뷰 부담 고려
- **Flyway 도입 완료 (V1~V3)**: 모든 스키마 변화는 Flyway 기반 incremental
- **서비스는 GCP VM 단일 배포** (pre-microservice)
- **프런트엔드 어드민(`pfplay-admin`)은 별 레포 + Cloudflare Workers 배포** (이미 구축)

## 2. Scope

### 2.1 MVP 기능 (A~H 카테고리)

#### A. 유저 관리
- **A-1.** 유저 목록 조회 (가입일, 이메일, 티어 기준 필터)
- **A-2.** 유저 상세 조회 (프로필, 활동 이력, 아바타 FACE/BODY 설정)
- **A-3.** 티어 조정 (운영/테스트 목적, 제재 아님)
- **A-4.** 탈퇴 처리 (비식별화: 이메일/비번/닉네임/아바타 익명화, userId 보존)

#### B. 파티룸 관리
- **B-1.** 룸 목록 조회 + 필터/정렬/세그먼트 뷰 (상세는 §7)
- **B-2.** 룸 상세 조회 (크루 목록, 재생 상태, DJ 큐)
- **B-3.** 룸 강제 종료 (사유 기록)
- **B-4.** 룸 일시 정지(SUSPEND)/재개(RESTORE)
- **B-5.** 룸 제목/소개 강제 수정
- **B-6.** 룸 display flag (FEATURED/HIDDEN/NORMAL)
- **B-7.** 운영자로서 파티룸 내부에서 크루 페널티 부과 (기존 crew penalty API 재사용)
- **B-8.** 일괄 액션 (다중 선택 종료 등)

#### C. 모더레이션
- **C-1.** 파티룸 신고 접수 (유저→어드민)
- **C-2.** 신고 목록/검토/해결 (PENDING → REVIEWING → RESOLVED/DISMISSED)
- **C-3.** 금지어/필터 관리 (채팅 송신 전 필터, 프로필 닉네임 필터)

#### D. 커뮤니케이션
- **D-1.** 전체 공지 등록/관리
- **D-2.** 팝업/배너 관리
- **D-3.** 푸시 알림 예약/발송

#### E. 시스템 운영
- **E-1.** 유지보수 모드 토글 (Spring Filter + `system_config`)
- **E-2.** 서비스 메트릭 (DAU, 룸 수, 재생 수 등 — Amplitude 병용 검토)
- **E-3.** Feature flag (MVP 후순위, `system_config` 재활용 예정)

#### F. 어드민 거버넌스 (슈퍼어드민 전용)
- **F-1.** 어드민 계정 CRUD
- **F-2.** 권한 — MVP는 2단계(SUPER_ADMIN, ADMIN), 향후 RBAC 세분화 확장 여지

#### G. 고객 지원
- **G-1.** 문의 조회/응답 (채널: 인앱/외부 중 선택 — §6.G 결정 필요)
- **G-2.** 개별 유저 조치 이력 조회

#### H. 가상 유저/데모
- **H-1.** 가상 유저 대량 생성 (local/dev 전용, prod에선 차단)
- **H-2.** 데모 환경 초기화 (local/dev 전용)

### 2.2 Out of Scope (MVP 아님, 미래 기능)

- **친구 맺기**: `friendship (user_account_id_a, _b, status, ...)` 스타일. 전용 "Social" 컨텍스트 신설 예상. 현 설계가 이를 막지 않음 확인.
- **1:1 대화 (DM)**: 대화 이력 저장 필요. `conversation`, `direct_message` 테이블 예상. 전용 "Messaging" 컨텍스트 신설 예상.
- **DM 모더레이션**: DM 신고/조사 기능. 신고 시스템이 polymorphic `report` 테이블로 진화할 수 있음.
- **감사 로그 고급 기능** (`admin_audit_log` before/after 스냅샷, SOC/규정 준수용)
- **세션/강제 로그아웃**: 제재는 파티룸 단위라는 원칙. JWT 블랙리스트 등은 안 만듦.
- **콘텐츠 신고 중 플레이리스트/트랙 단위 신고**: 지금은 파티룸 신고만.
- **환불/보상**: 유료 모델 없음.
- **RBAC 세분화**: 권한 매트릭스 기반 어드민 유형 구분. 2단계로 충분한 동안 추가 안 함.

### 2.3 Non-Goals (명시적 비목표)

- **호스트 강제 교체/박탈**: 파티룸 호스트는 생성자가 영구 보유. 문제 시 종료/일시 정지로 대응.
- **채팅/리액션 개별 강제 삭제**: 채팅은 DB 저장 안 됨. 리액션 개별 삭제는 도메인 가치 낮음. 전체 채팅 잠금은 추후 검토.
- **유저 계정 정지(Global suspension)**: 제재는 파티룸 단위만. 유저 계정 자체를 "정지 상태"로 만드는 기능은 없음.

## 3. Domain Model

### 3.1 Bounded Contexts

네 개의 명확한 컨텍스트로 나눈다.

| Context | Type | 관심사 | 주요 Aggregate |
|---|---|---|---|
| **IAM** | Generic subdomain | 인증 자격, 로그인, 계정 lifecycle | UserAccount |
| **Party** | **Core domain** | 파티, 음악, 크루, DJ, 페널티, 리액션 | Member, Guest, Partyroom, Crew, Playback, Playlist, DJQueue, CrewPenaltyHistory |
| **Administration** | Supporting subdomain | 시스템 운영 — 어드민, 관리 액션, 신고, 활동 로그 | Administrator, AdminAction, PartyroomReport, UserActivityLog |
| **Operations** | Supporting subdomain | 시스템 운영 상태 — 유지보수, feature flag | SystemConfig |

### 3.2 Cross-Context Integration 원칙

**핵심 규칙** (우선순위 순):

#### 규칙 1. Cross-context FK 금지

컨텍스트 경계를 넘는 외래키 제약은 두지 않는다.

- **예**: `member.user_account_id`는 IAM의 `user_account.user_id`를 참조하지만 `FOREIGN KEY` 제약 없음.
- **대안**: UNIQUE 제약(필요 시) + 애플리케이션 불변식으로 보호.
- **동일 컨텍스트 내 FK는 허용** — `administrator.granted_by_administrator_id → administrator.administrator_id` 같은 경우.

**이유**:
- 컨텍스트 진화/리팩토링 시 FK 제약이 걸림돌이 되는 것을 원천 차단
- 향후 microservice 분리 시 FK 제거 비용 0
- 컨텍스트가 서로의 내부 모델에 암묵적으로 결합되는 것을 DB 레벨에서 방지

**비용**:
- DB가 못 잡는 무결성을 앱이 잡아야 함 → 테스트 커버리지 필수
- Orphan 레코드 가능성 존재 → 도메인 이벤트 + 배치 detection으로 대응 (§8)

#### 규칙 2. Shared Identifier 공유

`UserAccountId`, `PartyroomId`, `CrewId` 등 식별자는 컨텍스트 간 값(value)으로 공유된다.

- 각 컨텍스트는 해당 식별자를 **값으로만** 보유
- 값이 가리키는 대상의 내부 모델을 import 하지 않는다
- Administration의 `AdminAction`은 `targetType` + `targetId`로 Party 엔티티를 추적하지만, Party의 JPA 엔티티를 참조하거나 import 하지 않는다

#### 규칙 3. Domain Event 기반 통합

컨텍스트 간 상태 전파는 도메인 이벤트로 수행한다.

- 프로젝트는 이미 `ApplicationEventPublisher`를 사용 중 (`CrewAccessedEvent` 등)
- 추가 이벤트 예시:
  - `UserAccountWithdrawn` (IAM 발행) → Party/Administration 리스너가 익명화
  - `PartyroomSuspendedByAdmin` (Party 발행) → Administration `UserActivityLog` 리스너가 기록
  - `AdminPenalizedCrew` (Administration 또는 Party 발행) → 양 컨텍스트에서 각자 기록

#### 규칙 4. Repository & Package 경계

각 컨텍스트는 독립된 패키지로 구분되어야 한다.

- 권장 패키지 구조:
  ```
  com.pfplaybackend.api.iam.*             ← UserAccount, auth 관련
  com.pfplaybackend.api.party.*           ← Member, Guest, Partyroom 등
                                             (기존 user.* 와 party.*의 재편/통합)
  com.pfplaybackend.api.administration.*  ← Administrator, AdminAction 등
                                             (기존 admin.* 의 재편)
  com.pfplaybackend.api.operations.*      ← SystemConfig, maintenance
  ```
- Static import 체크: Administration 코드에서 `com.pfplaybackend.api.party.*` 클래스 import 금지 (ArchUnit 또는 정적 분석 도구 활용 가능)
- **예외**: `common` 모듈의 공통 값 객체(UserId, PartyroomId 등)는 전 컨텍스트에서 참조 가능 — Shared Kernel로 취급

### 3.3 Aggregates by Context

각 Aggregate는 Bounded Context 내부의 일관성 경계다. Aggregate Root만이 직접 조회/수정 대상이며, 내부 엔티티는 root를 통해서만 접근한다.

#### 3.3.1 IAM

**`UserAccount` (Aggregate Root)**

속성:
- `userAccountId` (PK)
- `email` (unique, 전역)
- `providerType` (GOOGLE | TWITTER | LOCAL)
- `passwordHash` (nullable, LOCAL일 때만 세팅)
- `lastLoginAt` (nullable)
- `withdrawnAt` (nullable)
- `createdAt`, `updatedAt`

불변식:
- `providerType=LOCAL` ⟺ `passwordHash` 존재
- `email`은 전역 unique
- `withdrawnAt` 설정 후 로그인 불가 (애플리케이션 검증)

팩토리:
- `UserAccount.forSocial(email, providerType)` — passwordHash=null
- `UserAccount.forLocal(email, passwordHash)` — 어드민 프로비저닝용

라이프사이클 종료:
- `withdraw()` — email/password/lastLogin 익명화, `withdrawnAt` 설정
- `UserAccountWithdrawn` 도메인 이벤트 발행

#### 3.3.2 Party

**`Member` (Aggregate Root)**

속성:
- `memberId` (PK)
- `userAccountId` (값 참조, UNIQUE)
- `authorityTier` (FM | AM | GT) — 파티 서비스 등급
- `profile` (nickname, introduction, walletAddress, avatar settings)
- `activityData` (1:N 관계, DJ_PNT / REF_LINK / ROOM_ACT 점수)

불변식:
- 같은 `userAccountId`로 Member 최대 1개 (UNIQUE 제약)
- 같은 UserAccount가 Guest와 Member 동시 보유 불가 (애플리케이션 검증)

참고: 기존 `UserAccountData → MemberData` 상속 구조에서 composition으로 전환 (§4에서 상세).

**`Guest` (Aggregate Root)**

속성:
- `guestId` (PK)
- `userAccountId` (값 참조, UNIQUE)
- `agent` (User-Agent string, 디바이스 식별)

라이프사이클: Guest가 Member로 승격되면 Guest 레코드 삭제 + Member 레코드 생성 (원자적).

**`Partyroom` (Aggregate Root)** — 큰 변경 대상

속성 추가:
- `status` ENUM('ACTIVE', 'SUSPENDED', 'TERMINATED') — 기존 `isTerminated` 대체
- `crewCount` INT DEFAULT 0 — denormalized 카운터
- `lastActivityAt` DATETIME nullable
- `displayFlag` ENUM('NORMAL', 'FEATURED', 'HIDDEN')

불변식 (V6 리팩토링으로 enforce):
- `SUSPENDED` 상태 — 신규 crew 입장 거부. 기존 crew는 그대로 유지.
- `TERMINATED` 상태 — 모든 쓰기 거부 (페널티, 채팅, 재생 변경 등).
- `crewCount`는 활성 crew 수와 일치 (crew enter/exit 이벤트로 유지).

정책:
- `displayFlag`는 Party 엔티티에 위치하지만 **Administration 서비스만 쓰기 권한**. Party 비즈니스 로직은 읽지도 쓰지도 않는다.

**`Crew`, `Playback`, `Playlist`, `DJQueue`**: 기존 구조 유지. `Partyroom.status` 기반 invariant 체크만 추가.

**`CrewPenaltyHistory` / `CrewBlockHistory`**

속성 추가:
- `punisherType` ENUM('CREW', 'ADMIN') — 누가 제재했는지 **유형만**

**중요**: 어드민 정체(`administratorId`)는 Party 테이블에 저장하지 **않는다**. Administration의 `AdminAction`에 별도 기록. 필요 시 correlation (시간 + partyroomId + crewId)로 join.

#### 3.3.3 Administration

**`Administrator` (Aggregate Root)**

속성:
- `administratorId` (PK)
- `userAccountId` (값 참조, UNIQUE)
- `role` VARCHAR(32) — 'SUPER_ADMIN' | 'ADMIN' (enum 대신 VARCHAR: MySQL ENUM은 값 추가 시 ALTER TABLE 필요 → RBAC 확장 시 운영 방해)
- `grantedByAdministratorId` (FK 내부, self-ref, nullable — 슈퍼어드민은 null)
- `grantedAt`, `revokedAt`
- `createdAt`, `updatedAt`

불변식:
- `SUPER_ADMIN`은 전 시스템에서 유일 (DB functional unique index + 애플리케이션 검증)
  ```sql
  CREATE UNIQUE INDEX uk_administrator_super_admin
    ON administrator ((CASE WHEN role='SUPER_ADMIN' THEN 1 ELSE NULL END));
  ```
- 같은 `userAccountId`로 Administrator 최대 1개 (UNIQUE 제약)
- `userAccountId`가 가리키는 UserAccount는 `providerType=LOCAL`이어야 함 (애플리케이션 검증, DB FK 없음)

생성:
- 슈퍼어드민 시딩: Flyway V5가 placeholder로 insert, `ApplicationReadyEvent` 훅이 env 기반 비번으로 교체 (§5 상세)
- 일반 어드민: 슈퍼어드민이 `POST /api/v1/admin/system/administrators`로 생성

**`AdminAction` (Aggregate Root — append-only audit)**

속성:
- `actionId` (PK)
- `administratorId` (FK 내부)
- `actionType` VARCHAR(32) — SUSPEND_PARTYROOM / RESTORE_PARTYROOM / TERMINATE_PARTYROOM / SET_FEATURED / SET_HIDDEN / PENALIZE_CREW / UPDATE_PARTYROOM_META 등
- `targetType` VARCHAR(16) — 'PARTYROOM' | 'CREW' | 'MEMBER' 등
- `targetId` BIGINT — 대상 ID (loose ref, 컨텍스트 넘어가는 대상 포함)
- `reason` TEXT nullable
- `metadata` JSON nullable (actionType별 추가 데이터)
- `occurredAt` DATETIME

속성: Append-only. 생성만, 수정/삭제 없음. 감사 목적.

**`PartyroomReport` (Aggregate Root)**

속성:
- `reportId` (PK)
- `partyroomId` (값 참조, loose ref)
- `reporterUserAccountId` (값 참조, loose ref)
- `category` ENUM('INAPPROPRIATE_CONTENT', 'HARASSMENT', 'SPAM', 'COPYRIGHT', 'OTHER')
- `description` TEXT nullable — 신고자 추가 설명
- `status` ENUM('PENDING', 'REVIEWING', 'RESOLVED', 'DISMISSED')
- `reviewedByAdministratorId` (FK 내부, nullable)
- `resolutionNote` TEXT nullable — 어드민 처리 메모
- `createdAt`, `resolvedAt`

상태 전이:
- `PENDING → REVIEWING` (어드민이 검토 시작)
- `REVIEWING → RESOLVED` (해결 완료 — 파티룸 액션 취하거나 별 조치 없이)
- `REVIEWING → DISMISSED` (무효 신고로 판단)

**`UserActivityLog` (Aggregate Root — append-only audit)**

속성:
- `logId` (PK)
- `userAccountId` (값 참조, loose ref)
- `eventType` VARCHAR(64)
- `partyroomId` (값 참조, loose ref, nullable)
- `metadata` JSON nullable
- `occurredAt` DATETIME

기록 대상 이벤트:
| eventType | 의미 |
|---|---|
| `SIGNED_UP` | 가입 |
| `SIGNED_IN` | 로그인 |
| `WITHDREW` | 탈퇴 |
| `PROFILE_UPDATED` | 닉네임/소개/아바타 변경 |
| `TIER_CHANGED` | 어드민이 티어 조정 |
| `PARTYROOM_CREATED` | 룸 생성 |
| `PARTYROOM_ENTERED` | 룸 입장 |
| `PARTYROOM_EXITED` | 룸 퇴장 (duration metadata 포함) |
| `PENALIZED_IN_PARTYROOM` | 크루 페널티 받음 |
| `ADMIN_ACTED_ON` | 어드민 액션 대상이 됨 |

제외 이벤트 (Amplitude로 충분):
- 채팅 메시지, 리액션, 검색, 트랙 재생 시작 등 고빈도/비-audit 이벤트

#### 3.3.4 Operations

**`SystemConfig` (Aggregate Root)**

속성:
- `configKey` VARCHAR(64) (PK)
- `configValue` TEXT
- `description` VARCHAR(255) nullable
- `updatedByAdministratorId` BIGINT nullable (값 참조, loose ref — Administration 영역)
- `updatedAt` DATETIME

MVP 용도:
- `maintenance.enabled`: `"true"` | `"false"`
- `maintenance.message`: 유지보수 안내 메시지
- `maintenance.started_at`: ISO timestamp
- 향후 feature flag로 확장: `feature.xxx.enabled` 등

캐시: 런타임 조회는 Redis 또는 애플리케이션 캐시 (30~60초 TTL). 변경 시 `SystemConfigUpdated` 이벤트 발행 → 캐시 무효화.

### 3.4 Context Map

```
                          ┌────────────────────┐
                          │   IAM              │
                          │   UserAccount      │
                          └────────┬───────────┘
                                   │
                       userAccountId (value, no FK)
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
        ┌───────▼───────┐  ┌───────▼─────────┐  ┌────▼──────────┐
        │   Party       │  │ Administration  │  │  Operations   │
        │               │  │                 │  │               │
        │ Member        │  │ Administrator   │  │ SystemConfig  │
        │ Guest         │  │ AdminAction ────┼──► (target: Party│
        │ Partyroom     │  │ PartyroomReport │  │   entities as │
        │ Crew          │  │ UserActivityLog │  │   value refs) │
        │ Playback      │  │                 │  │               │
        │ Playlist      │  │                 │  │               │
        │ DJQueue       │  │                 │  │               │
        │ CrewPenalty   │  │                 │  │               │
        └───────┬───────┘  └─────────▲───────┘  └───────────────┘
                │                    │
                │  domain events     │
                └───────────────────►│
                                     │
                              (Administration listens to
                               Party events for UserActivityLog)
```

- **실선 화살표**: 값 참조 (UserAccountId, PartyroomId 등). FK 아님.
- **점선 화살표**: 도메인 이벤트 기반 통합.
- **각 컨텍스트는 자기 자신의 테이블/엔티티만 owns**.

## 4~11 섹션

본 문서의 분량이 커짐에 따라, 구현 세부는 별도 문서로 분리한다. 본 문서(§1~§3)는 설계의 **철학과 도메인 모델**을 담고, 아래 문서들은 **구현 세부**를 담는다.

| 섹션 | 문서 | 내용 |
|---|---|---|
| §4 Schema Design | `2026-04-19-admin-platform-schema.md` | V4~V11 스키마 + 마이그레이션 전략 |
| §5 Security Design | `2026-04-19-admin-platform-security.md` | 인증, 인가, 쿠키, 하드닝 |
| §6~§7 Features & Listing UI | `2026-04-19-admin-platform-features.md` | A~H 기능 + 파티룸 목록 UI 템플릿 |
| §8 Integrity Enforcement | `2026-04-19-admin-platform-integrity.md` | FK 없이 무결성 보장 전략 |
| §9~§11 Roadmap & Decisions | `2026-04-19-admin-platform-roadmap.md` | PR 분할, 리뷰 반영, open decisions |

모든 하위 문서는 본 문서의 섹션 번호 규약을 따른다.

---

**Revision history**:
- 2026-04-19 Initial narrow auth design (now archived in git history as predecessor of this file)
- 2026-04-19 Expanded to full platform design (this revision)
