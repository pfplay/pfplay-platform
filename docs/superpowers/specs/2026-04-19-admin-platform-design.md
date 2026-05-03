# PFPlay Admin Platform Design

> **Status**: Draft (supersedes earlier `admin-auth-design` scope)
> **Scope**: 어드민 플랫폼 전체 — 인증, 권한, 유저/파티룸/모더레이션/공지/시스템 운영
> **Audience**: 백엔드 엔지니어, 프론트엔드 엔지니어, 운영/CS 팀

## 0. TL;DR

PFPlay는 파티룸 기반 음악 스트리밍 서비스다. 본 문서는 `pfplay-admin` 프런트엔드와 이를 뒷받침할 백엔드 어드민 플랫폼의 중장기 설계를 정의한다. 서비스는 pre-launch 상태이며, 이 설계를 기점으로 10개의 Flyway 마이그레이션(V4~V13, V12 슬롯 채워짐)과 다수의 PR로 구현된다.

핵심 설계 결정:
- **F2 (Composition)** 바운디드 컨텍스트 분리: IAM / User Profile / Party / Playlist / Avatar / Administration / Operations + Realtime(Runtime-segregated) + Shared Kernel(`common`)
- **Avatar BC 신설 (신규)** — 아바타 리소스는 서비스 내 유일한 잠재 과금 영역이라 전략적 분리. `avatar` Gradle 모듈 신설.
- **Cross-context FK 금지** — 무결성은 애플리케이션 레이어 + 도메인 이벤트로 보장
- **슈퍼어드민 1명 + 일반 어드민 N** (확장 가능한 RBAC 기반)
- **어드민 로컬 로그인 + 크로스-서브도메인 쿠키** (admin.pfplay.xyz ↔ pfplay.xyz 간 세션 공유)
- **Flyway 중심 스키마 진화** — Java initializer는 도메인 로직 동반 시드에만 사용
- **친구/DM 같은 미래 기능에 대한 스키마 과설계 금지** — YAGNI 준수 (단, Avatar BC의 향후 `obtainable_type` 확장은 과금 전략 대비로 설계 상 차단되지 않도록 함)

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

#### I. 아바타 리소스 관리 (SUPER_ADMIN 전용)
- **I-1.** 아바타 카탈로그 조회 (DRAFT/PUBLISHED/RETIRED 전체)
- **I-2.** 리소스 생성 (GCS 백엔드 프록시 업로드 + DB 등록, 원자성 보장)
- **I-3.** 리소스 수정 (필드 패치 + 이미지 교체 — 과금 대비 lifecycle 제약)
- **I-4.** 상태 전이 (publish/retire, 하드 delete 불가)
- **I-5.** 아이콘 전용 재업로드 엔드포인트
- **동기**: 아바타는 서비스 내 유일한 잠재 과금 영역. 현재 Java initializer/Flyway V3 시드 기반 관리의 고통(새 리소스 추가, 교체, DJ_PNT 튜닝, Firebase-DB 수작업 복붙, 페어링) 해소.

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

PFPlay 전체를 **7개 비즈니스 BC + 1 Runtime-segregated 모듈 + Shared Kernel**로 정리한다. 이는 현 Gradle 멀티모듈 구조와 일치시키고, Avatar BC를 새로 분리한 재편이다.

| # | Context | Type | Gradle 모듈 / 패키지 | 주요 Aggregate | 관심사 |
|---|---|---|---|---|---|
| 1 | **IAM** | Generic subdomain | **현재 분산**: `common/.../config/security/*` (필터/JWT 인프라) + `app/.../auth/*` (OAuth·로그인 엔드포인트) + `user/.../api/user/identity/*` (UserAccount aggregate). 중장기 후보: `iam` 모듈 분리. | `UserAccount` | 로그인 자격, JWT 발급/검증, OAuth, 비밀번호 |
| 2 | **User Profile** | Supporting | `user` 모듈, `api.user.profile.*` 패키지 | `Member`, `Guest` | 티어·닉네임·소개·지갑·아바타 설정(유저가 고른 URI) |
| 3 | **Party** | **Core domain** | `app` 모듈, `api.party.*` + `api.partyview.*` | `Partyroom`, `Crew`, `Playback`, `DjQueue`, `CrewPenaltyHistory`, `CrewBlockHistory` | 파티·음악·크루·DJ·페널티·리액션 |
| 4 | **Playlist** | Supporting | `playlist` 모듈 | `Playlist`, `Track` | 개인 플레이리스트 — DJ 큐가 인용 |
| 5 | **Avatar** 🆕 | Supporting | **신규 `avatar` 모듈** | `AvatarBodyResource`, `AvatarFaceResource` | 아바타 카탈로그. 유일한 잠재 과금 영역(전략적으로 분리). 파일 업로드(GCS) 포함. |
| 6 | **Realtime** | — *(BC 아님; 런타임 분리 모듈)* | `realtime` 모듈 | (도메인 aggregate 없음) | WebSocket/STOMP 기반 실시간 브로드캐스트. 향후 WebFlux(reactive) 런타임으로 분리 운용 전제로 모듈 경계를 둠. 비즈니스 BC 유형(Generic/Core/Supporting)에 속하지 않음. |
| 7 | **Administration** | Supporting | `app` 모듈, `api.administration.*` (기존 `api.admin.*` 재편) | `Administrator`, `AdminAction`, `PartyroomReport`, `UserActivityLog` | 어드민 거버넌스, 모더레이션, 감사 |
| 8 | **Operations** | Supporting | `app` 모듈, `api.operations.*` (신규) | `SystemConfig` | 유지보수 모드, feature flag |
| — | Shared Kernel | — | `common` 모듈 | (값 객체·예외·보안 인프라) | 전 BC가 import 허용. BC 아님. |
| — | Bootstrap | — | `app.bootstrap.*` | — | 컴포지션 루트 / 시작 시퀀스. BC 아님. |

**변경점 요약**:
- 이전 설계는 4 BC(IAM / Party / Administration / Operations)였으나, **실제 Gradle 모듈 구조**(`user`, `playlist`, `realtime`)와 정합시키며 확장.
- **Avatar BC 신설**은 향후 과금 대응을 위한 전략적 분리. `user` 모듈에 흩어져 있던 `AvatarBody/Face/IconResource` 엔티티를 `avatar` 모듈로 이관.
- `user` 모듈 내부는 **IAM(identity) + User Profile** 두 BC를 패키지로 분리.
- `realtime`을 기존 "Technical subdomain" 표기에서 **"Runtime-segregated"로 재명명** — 분리 이유가 플럼빙이 아니라 런타임 스택 선택(WebFlux 전환 전제)임을 명확히.

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
  - `AvatarResourcePublished` (Avatar 발행) → Administration 리스너가 `admin_action` 기록, User Profile 피커 캐시 무효화 (캐시 도입 시점)
  - `AvatarResourceRetired` (Avatar 발행) → Administration 리스너가 `admin_action` 기록. 기존 유저 `avatarSetting`에는 영향 없음(URI 값 참조).

#### 규칙 4. Module / Package 경계

각 컨텍스트는 Gradle 모듈 또는 패키지로 구분된다. Gradle 모듈 분리된 경우 컴파일러가 경계를 강제하고, 패키지 분리인 경우 정적 검사(ArchUnit)로 보호한다.

- 최종 모듈/패키지 구조 (V12 이후):
  ```
  avatar/                                   ← 신규 Gradle 모듈 (PR 10)
    └── com.pfplaybackend.api.avatar.*      ← Avatar BC 전체

  user/
    ├── com.pfplaybackend.api.user.identity.*  ← IAM 일부 (UserAccount)
    └── com.pfplaybackend.api.user.profile.*   ← User Profile BC (Member/Guest)

  playlist/  com.pfplaybackend.api.playlist.*
  realtime/  com.pfplaybackend.realtime.*      ← WebSocket/STOMP
  app/
    ├── com.pfplaybackend.api.auth.*             ← IAM 엔드포인트
    ├── com.pfplaybackend.api.party.*            ← Party BC + partyview
    ├── com.pfplaybackend.api.partyview.*        ← (Party 내 read-model)
    ├── com.pfplaybackend.api.administration.*   ← 기존 api.admin.* 재편
    ├── com.pfplaybackend.api.operations.*       ← 신규 (SystemConfig)
    └── com.pfplaybackend.api.bootstrap.*        ← 컴포지션 루트

  common/  (Shared Kernel — 전 BC 참조 허용)
  ```
- **ArchUnit 경계 규칙 (§8.5)**:
  - Administration → Party 내부 엔티티 import 금지 (값 객체만 허용)
  - Avatar → 그 어떤 다른 BC도 import 금지 (avatar는 순수 생산자). 역방향(User Profile/Administration/app → Avatar)은 허용.
  - `common` 모듈은 어떤 BC에도 의존하지 않는다 (Shared Kernel).
- **Gradle 레벨 강제**:
  - `avatar` 모듈은 `common`만 `implementation project(':common')`으로 의존.
  - `user` 모듈은 `avatar` 모듈을 추가 의존 (유저 피커가 avatar 카탈로그를 조회).

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

> 주: Member는 **User Profile BC**에 속한다 (§3.1 #2). 여기 §3.3.2 Party와 분리 배치는 과거 문서 구조 상 편의이며, Party BC와 User Profile BC는 별 BC다. V12 이후 패키지 경계가 명확해진다.

속성:
- `memberId` (PK)
- `userAccountId` (값 참조, UNIQUE)
- `authorityTier` (FM | AM | GT) — 파티 서비스 등급
- `profile` (nickname, introduction, walletAddress, **avatarSetting**)
- `activityData` (1:N 관계, DJ_PNT / REF_LINK / ROOM_ACT 점수)

`avatarSetting` 상세 (User Profile → Avatar BC 참조):
- `avatarBodyUri`, `avatarFaceUri`, `avatarIconUri` — 전부 **URI 문자열**
- Avatar BC의 리소스 ID가 아니라 URI 값으로 참조 (cross-BC FK 금지 원칙)
- Avatar 리소스가 retire되어도 기존 유저 설정은 URI 기반이므로 깨지지 않음
- 유저 피커는 `lifecycle_status='PUBLISHED'` 필터를 거쳐 유효 리소스만 선택 가능 (§6.I)

**V12 이후 `avatarIconUri` 의미론** (중요):
- V12로 `avatar_icon_resource` 테이블이 삭제되지만, `member.avatarSetting.avatarIconUri`는 **유지된다** (구조 변경 없음).
- 이 필드의 의미가 "독립된 AvatarIconResource를 향한 참조" → "유저가 고른 body 또는 face의 `icon_uri` 값을 **캐시한 것**"으로 바뀐다.
- 캐시 갱신 시점: 유저가 body 선택 시 → `body.icon_uri`를 복사. Face 선택 시 합성 구성에 따라 face 기반 또는 body 기반 아이콘을 복사 (기존 `UserAvatarDomainService` 로직과 동일 방향성).
- V12 Step 3의 `UPDATE body SET icon_uri = (icon row's resource_uri)`로 본래 존재했던 URI 문자열이 그대로 부모 테이블로 옮겨오므로, 기존 유저가 캐시해둔 `avatarIconUri` 값은 **V12 전후로 동일한 문자열 URI를 계속 가리킨다**. 데이터 이전 불필요.
- 코드 변화: `AvatarResourceQueryService.findByNameAndPairType(...)` 호출부는 `body.getIconUri()` / `face.getIconUri()` 직접 조회로 치환 (PR 10에 포함).

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

#### 3.3.5 Avatar 🆕

신규 `avatar` Gradle 모듈에 거주. 유일한 잠재 과금 영역을 위한 전략적 분리.

**`AvatarBodyResource` (Aggregate Root)**

속성:
- `id` (PK)
- `name` VARCHAR(64) UNIQUE — 전역 식별자 (예: `ava_body_djing_005`)
- `resourceUri` VARCHAR(500) — 바디 이미지 공개 URL (GCS)
- `iconUri` VARCHAR(500) nullable — 피커 썸네일 URL. NULL이면 placeholder 표시.
- `obtainableType` VARCHAR(16) — `BASIC | DJ_PNT` (향후 `PURCHASE | EVENT` 확장 대비, ENUM 확장은 Flyway로)
- `obtainableScore` INT — DJ_PNT일 때 해금 기준 점수
- `isCombinable` BOOLEAN, `isDefaultSetting` BOOLEAN
- `combinePositionX/Y` INT — face 합성 좌표
- `lifecycleStatus` VARCHAR(16) — `DRAFT | PUBLISHED | RETIRED`
- `createdAt`, `createdBy`, `updatedAt`, `updatedBy`
  - `createdBy`/`updatedBy`는 **`Long`** raw 저장 (administrator_id 값). `AdministratorId` VO를 import하지 않는다 — Avatar BC는 Administration BC에 의존하지 않는 순수 생산자 규약. 호출자(`AdminAvatarCommandService`)가 `AdministratorId.getValue()`로 언팩해 전달.
  - NULL = 시스템 시드(V3 이전 부팅)

**엔티티 기본값 주의**: V12는 `lifecycle_status`에 `DEFAULT 'PUBLISHED'`를 설정(기존 V3 15+1행을 PUBLISHED로 이전하기 위함). 그러나 **신규 레코드는 반드시 DRAFT로 들어가야** 하므로, `AvatarBodyResource.draft(...)` 팩토리는 컬럼 값을 명시적으로 `DRAFT`로 세팅해 INSERT한다 (DB default 의존 금지). 같은 규약이 Face에도 적용.

불변식:
- `name` 전역 UNIQUE
- lifecycleStatus 전이 **단방향**: `DRAFT → PUBLISHED → RETIRED`. 역방향 금지.
- `isDefaultSetting=true` ⟹ `obtainableType=BASIC` AND `lifecycleStatus=PUBLISHED`
- `obtainableType=BASIC` ⟹ `obtainableScore=0`
- `RETIRED` 상태에서는 수정 불가 (aggregate method가 차단)

팩토리:
- `AvatarBodyResource.draft(name, bodyUri, iconUri?, obtainable..., combine...)` — DRAFT 생성

명령:
- `updateResource(...)` — DRAFT 또는 PUBLISHED에서만
- `updateIconUri(uri)` — 아이콘 단독 교체
- `publish()` — DRAFT → PUBLISHED, `AvatarResourcePublished` 이벤트
- `retire(reason)` — PUBLISHED → RETIRED, `AvatarResourceRetired` 이벤트

**`AvatarFaceResource` (Aggregate Root)**

속성:
- `id` (PK), `name` UNIQUE, `resourceUri`, `iconUri` nullable
- `obtainableType` VARCHAR(16) `DEFAULT 'BASIC'` — 현재 BASIC 고정. 향후 과금 확장 대비 컬럼 선행 도입.
- `lifecycleStatus`, 감사 컬럼(Body와 동일)

Body와 동일한 lifecycle 규약 + 도메인 이벤트.

**삭제된 개념**:
- `AvatarIconResource` 테이블/엔티티 — body/face에 `iconUri` 필드로 흡수 (§4 V12)
- `PairType` enum — 불필요 (테이블 DROP으로 discriminator 소멸)

**Avatar BC 포트**:
- `AvatarCatalogQueryUseCase` — User Profile/Admin 모두 사용
- `AvatarCatalogCommandUseCase` — SUPER_ADMIN 전용
- `AvatarStoragePort` — GCS 업로드 어댑터 추상화 (`adapter/out/storage/GcsAvatarStorageAdapter`가 구현)

### 3.4 Context Map

```
                   ┌────────────────────┐
                   │   IAM              │
                   │   UserAccount      │
                   └────────┬───────────┘
                            │
                userAccountId (value, no FK)
                            │
     ┌────────────┬─────────┼─────────┬─────────────┐
     │            │         │         │             │
┌────▼─────┐ ┌────▼──────┐  │  ┌──────▼──────┐ ┌────▼────────┐
│  User    │ │  Party    │  │  │Administration│ │ Operations │
│ Profile  │ │  (Core)   │  │  │              │ │            │
│          │ │           │  │  │Administrator │ │SystemConfig│
│ Member   │ │ Partyroom │  │  │AdminAction ──┼─►(targets as│
│ Guest    │ │ Crew      │  │  │PartyroomRpt  │ │ value refs)│
│          │ │ Playback  │  │  │UserActivLog  │ │            │
│ (avatar  │ │ DjQueue   │  │  │              │ │            │
│  URIs→)──┼─┤CrewPenlty │  │  └──────▲───────┘ └────────────┘
└────┬─────┘ └────┬──────┘  │         │
     │            │         │  domain events
     │        ┌───▼─────┐   │   ┌─────┴──────┐
     │        │Playlist │   │   │  Realtime  │
     │        │ (track  │   │   │ (WebFlux,  │
     │        │  catalg)│   │   │ broadcast) │
     │        └─────────┘   │   └────────────┘
     │                      │
     └──────────────────────┘
     URI value reference
     (avatar_body_uri, avatar_face_uri, avatar_icon_uri)
                            │
                    ┌───────▼─────────┐
                    │  Avatar 🆕      │
                    │                 │
                    │ AvatarBody      │
                    │ AvatarFace      │
                    │ (Resources)     │
                    │ + GCS uploads   │
                    └─────────────────┘

Shared Kernel: common (전 BC가 VO/예외/보안 인프라 import)
```

- **실선 화살표**: 값 참조 (UserAccountId, PartyroomId, Avatar URI 등). FK 아님.
- **점선/domain events**: 도메인 이벤트 기반 통합.
- **Avatar**는 유일한 "순수 생산자" BC — 다른 BC는 Avatar를 import하지만 Avatar는 어디도 import하지 않는다. (Gradle이 이 방향을 강제)
- **Realtime**은 도메인 aggregate 없음 — 다른 BC가 발행한 브로드캐스트를 소비해 WebSocket으로 내보낸다.
- **각 BC는 자기 자신의 테이블/엔티티만 owns**.

## 4~11 섹션

본 문서의 분량이 커짐에 따라, 구현 세부는 별도 문서로 분리한다. 본 문서(§1~§3)는 설계의 **철학과 도메인 모델**을 담고, 아래 문서들은 **구현 세부**를 담는다.

| 섹션 | 문서 | 내용 |
|---|---|---|
| §4 Schema Design | `2026-04-19-admin-platform-schema.md` | V4~V13 스키마 + 마이그레이션 전략 |
| §5 Security Design | `2026-04-19-admin-platform-security.md` | 인증, 인가, 쿠키, 하드닝 |
| §6~§7 Features & Listing UI | `2026-04-19-admin-platform-features.md` | A~H 기능 + 파티룸 목록 UI 템플릿 |
| §8 Integrity Enforcement | `2026-04-19-admin-platform-integrity.md` | FK 없이 무결성 보장 전략 |
| §9~§11 Roadmap & Decisions | `2026-04-19-admin-platform-roadmap.md` | PR 분할, 리뷰 반영, open decisions |

모든 하위 문서는 본 문서의 섹션 번호 규약을 따른다.

---

**Revision history**:
- 2026-04-19 Initial narrow auth design (now archived in git history as predecessor of this file)
- 2026-04-19 Full admin platform design (§0~§11 across 6 docs), post architecture-review iteration
- 2026-04-20 Avatar BC 신설 및 BC 재편 (4 → 7 BCs + Realtime + Shared Kernel). `avatar` Gradle 모듈 추가, IAM 현 분산 명시, Realtime을 Runtime-segregated로 재분류. §2.1 I 카테고리(아바타 리소스 관리) 추가. §3.1 BC 표 전면 재작성, §3.2 모듈 경계 규약 구체화, §3.3.5 Avatar aggregates 추가, §3.4 context map 재그림. 관련 V12 마이그레이션 및 §6.I 스펙은 별 문서. 아키텍처 리뷰 2차 반영 (`avatarIconUri` 캐시 의미론 명시, V12 JOIN 방어적 전환, lifecycle 기본값 규약 등).
- 2026-04-19 Expanded to full platform design (this revision)
