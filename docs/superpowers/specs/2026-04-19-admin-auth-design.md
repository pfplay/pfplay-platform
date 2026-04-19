# Admin Authentication & Authorization Design

## Context

pfplay-admin(React + Vite SPA, Cloudflare Workers 배포)에 로그인/인증 기능을 도입한다. 현재 상태:

- `pfplay-admin` 프런트: 인증 로직 전무, 토큰 없이 API 호출
- `pfplay-platform` 백엔드: `/api/v1/admin/**`이 `permitAll()` 상태 ("temporary" 주석, [SecurityConfig.java:42](../../../common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java)), 실질 보호 없음
- `pfplay-web` 고객 웹: PKCE 기반 OAuth2 (Google/Twitter)만 사용, 로컬 로그인 없음

고객용 소셜 로그인과 어드민 인증은 독립된 창구가 필요하며, 백엔드 보안/도메인 모델 개편이 동반되어야 한다.

## Goals

- `/api/v1/admin/**` 엔드포인트를 실질적으로 보호
- 어드민 계정 체계: 슈퍼어드민 1명 + 일반 어드민 N명 (슈퍼어드민이 어드민 CRUD)
- 어드민이 필요 시 Party 컨텍스트에서 Member(크루)로도 활동 가능 — 예: 메인 파티룸 HOST
- 기존 소셜 회원 시스템과 공존 (간섭 없음)

## Non-goals

- 기존 소셜 회원(고객)을 어드민으로 승격하는 플로우 — **명시적으로 제외**
- 다중 슈퍼어드민 — 슈퍼어드민은 유일
- MFA, IP 제한, 감사로그 — 본 이터레이션 범위 밖 (향후 확장 여지만 남김)
- 어드민 계정의 소셜 로그인 — 로컬 이메일+비밀번호만 지원

## Key Constraints

1. **슈퍼어드민 유일**: 시딩으로만 존재, API로 추가 생성 불가
2. **어드민은 로컬 계정만**: 슈퍼어드민이 신규 이메일로 프로비저닝
3. **고객 → 어드민 승격 금지**: 존재하지 않는 플로우 (도메인 정책)
4. **스키마 재설계 허용**: 서비스 미오픈 상태, `pfplay-platform`의 DB 스키마는 자유롭게 갈아엎을 수 있음
5. **어드민의 Member 겸임은 옵션**: 어드민 계정 생성 시 "Member 프로필도 만들지" 플래그로 선택

## Domain Analysis — Bounded Contexts

DDD 관점에서 pfplay는 최소 3개의 bounded context로 나뉜다:

| Bounded Context | Type | 주요 모델 | 관심사 |
|---|---|---|---|
| **IAM / Identity** | Generic subdomain | `UserAccount` | "누구인가" — 이메일, 인증 자격 (비밀번호 or 소셜 토큰), provider |
| **Party** | Core domain | `Member`, `Partyroom`, `Crew`, `DJQueue`, `Host` | "파티에서 무엇을 하는가" — 음악, 크루, DJ |
| **Administration** | Supporting subdomain | `Administrator`, `AdminGrant` | "시스템을 어떻게 운영하는가" — 유저/룸 관리, 권한 위임 |

**핵심 원칙**: 한 Person은 각 컨텍스트에서 서로 다른 Role로 등장한다. 세 컨텍스트는 `userAccountId`를 공유 축으로 연결된다 (Context Map의 Shared Identity 패턴).

## Chosen Approach: F2 (Composition)

세 aggregate를 독립 엔티티로 두고 FK로 참조.

```
  UserAccount (IAM aggregate root, 필수)
       ↑ 1
       │
   ┌───┴────────────────┐
   │ 0..1            0..1 │
   Member            Administrator
   (Party aggregate)  (Administration aggregate)
   FK: userAccountId  FK: userAccountId
```

한 UserAccount는 Member, Administrator 각각 0 또는 1개 가질 수 있다. 조합 시나리오:

| 시나리오 | UserAccount | Member | Administrator |
|---|---|---|---|
| 일반 고객 | ✓ (providerType=GOOGLE/TWITTER) | ✓ | ✗ |
| HOST 크루 겸 어드민 | ✓ (providerType=LOCAL) | ✓ | ✓ |
| 시스템 운영 전담 (파티 참여 X) | ✓ (providerType=LOCAL) | ✗ | ✓ |
| 슈퍼어드민 | ✓ (providerType=LOCAL) | 선택 | ✓ (role=SUPER_ADMIN) |

## Why Not the Alternatives

### ❌ Option A — 완전 분리된 Admin 전용 계정
어드민이 HOST 크루로 활동해야 하므로 Member 레코드도 따로 만들어 페어링해야 함 → 이점 사라짐, 복잡도만 증가.

### ❌ Option B — `MemberData`에 password/adminRole 필드 추가
Party Context의 aggregate가 IAM/Administration 관심사를 떠안음. Bounded Context 경계 파손, 단일 책임 위반.

### ❌ Option F1 — `MemberData extends UserAccountData`, `AdministratorData extends UserAccountData` (JPA JOINED inheritance)
JPA JOINED 전략은 **한 row가 하나의 discriminator 값만 가질 수 있다**. 같은 userId로 Member이면서 동시에 Administrator일 수 없음 → HOST 크루 요구사항 충돌. **실행 불가**.

### ✅ Option F2 — Composition (본 설계)
세 aggregate가 독립 엔티티로 존재하고 FK로만 연결. JPA inheritance 제약 회피, DDD 경계 준수, 요구사항 전부 충족.

## Key Findings from Code Exploration

설계에 반영해야 하는 기존 코드 상태:

1. **`UserAccountData`가 이미 `@Inheritance(JOINED)` + `@DiscriminatorColumn`** ([UserAccountData.java:17-22](../../../user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java))
   - 물리 테이블 `USER_ACCOUNT`(부모) + `MEMBER`(자식) 이미 분리됨
   - 하지만 F2로 가려면 상속 제거 후 composition 참조로 전환 필요

2. **`authorityTier`는 현재 부모(`UserAccountData`)에 있음**, Member 특화가 아님
   - 본 설계에선 Party Context 개념이므로 `Member`로 내린다

3. **`ProviderType`에 이미 `ADMIN` 값 존재** ([ProviderType.java](../../../common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java))
   - 현재 용도: "admin-created virtual members" (테스트/데모 가상 유저)
   - **혼동 유발**: 본 설계에서 어드민 본인 계정용으로는 `LOCAL` 값을 새로 추가하고, 기존 `ADMIN`은 용도 재검토 (rename 또는 가상유저 표기 유지)

4. **`AccessLevel.ROLE_ADMIN` enum 이미 정의됨** — 사용처는 없음. 본 설계에서 활성화

5. **`PasswordEncoder` Bean 없음** — 소셜 전용이었기에. 본 설계에서 `BCryptPasswordEncoder` 추가 필요

## Cross-Subdomain Login Strategy

어드민이 `admin.pfplay.xyz`에서 한 번 로그인하면 `pfplay.xyz` 방문 시에도 자동 인증되어야 한다 (HOST 크루 활동 시나리오).

**해결: JWT 쿠키의 `Domain=.pfplay.xyz` 스코프.**

```
1. 어드민 → admin.pfplay.xyz/login → 이메일+비번 제출
2. 백엔드: UserAccount + Administrator 검증 → JWT 발급
   Set-Cookie: AccessToken=...; Domain=.pfplay.xyz; HttpOnly; Secure; SameSite=Lax
3. 어드민 → pfplay.xyz 접속 → 동일 쿠키 자동 전송
4. pfplay-web이 /users/me/info 호출 → 200 (Member 존재) → 자동 로그인 상태
5. 메인 파티룸 입장 → HOST로 표시
```

pfplay-web에 별도 로그인 UI 추가 불필요. 쿠키 Domain 스코프만 올바르면 IAM 컨텍스트의 세션이 두 사이트에 공유됨.

## Login Flow Summary

| 플로우 | 엔드포인트 | 인증 방식 | 결과 JWT 클레임(초안) |
|---|---|---|---|
| 소셜 로그인 (기존 유지) | `/api/v1/auth/oauth/**` | Google/Twitter PKCE | `access_level=ROLE_MEMBER`, `memberId` |
| 어드민 로그인 (신규) | `/api/v1/auth/admin/login` | Email+Password (BCrypt) | `access_level=ROLE_ADMIN`, `adminRole`, `memberId?` |

JWT의 `sub`은 `userAccountId`로 통일 (Identity가 인증 주체).

## Admin Provisioning API (초안)

슈퍼어드민 전용:

```http
POST /api/v1/admin/system/administrators
Body:
{
  "email": "ops-new@pfplay.xyz",
  "role": "ADMIN",
  "includeMemberProfile": true,
  "memberProfile": { "nickname": "Ops-New", "authorityTier": "FM" }
}

Response 201:
{
  "administratorId": "...",
  "userAccountId": "...",
  "memberId": "..." | null,
  "tempPassword": "generated"
}
```

- `includeMemberProfile=true` → UserAccount + Administrator + Member 동시 생성
- `includeMemberProfile=false` → UserAccount + Administrator만
- `tempPassword`는 안전 채널로 전달, 첫 로그인 시 변경 강제

동적 변경용 보조 API:
- `POST .../administrators/{id}/grant-membership` — 이미 어드민인 사람에게 Member 프로필 추가
- `DELETE .../administrators/{id}` — Administrator 비활성화 (Member는 유지)

## SecurityConfig 개편 방향

```
/api/v1/auth/oauth/**           → permitAll
/api/v1/auth/admin/login        → permitAll (어드민 로그인 진입점)
/api/v1/auth/admin/**           → authenticated
/api/v1/admin/system/**         → hasRole('SUPER_ADMIN')  ← 어드민 계정 CRUD
/api/v1/admin/**                → hasRole('ADMIN')         ← 일반 어드민 기능
/api/v1/users/me/**             → authenticated
/api/v1/users/**                → permitAll                ← 공개 조회
/api/**                         → authenticated
/ws/**                          → permitAll
```

기존 `@PreAuthorize("hasAuthority('FM')")`로 어드민 보호하던 것은 의미상 잘못 — 전면 정리.

## Super Admin Seeding (방향)

권장: **Flyway V* 마이그레이션에서 placeholder 레코드 삽입 + 앱 시작 시 env 기반 교체**

```
1. Flyway V{n}__seed_super_admin.sql
   INSERT UserAccount (email=env.ADMIN_SEED_EMAIL, providerType=LOCAL, passwordHash='__PLACEHOLDER__')
   INSERT Administrator (userAccountId=..., role=SUPER_ADMIN, grantedBy=NULL)

2. ApplicationReadyEvent 훅
   if SUPER_ADMIN의 passwordHash == '__PLACEHOLDER__':
      passwordHash ← bcrypt(env.ADMIN_SEED_PASSWORD)
      save
```

- 환경변수로 초기 비번 주입, 로테이션 용이
- 재현 가능 (Flyway가 레코드 존재 보장)
- 운영 환경에선 env가 설정돼 있으면 즉시 활성화, 없으면 placeholder 상태 유지 (placeholder 상태에선 로그인 불가)

## Frontend (pfplay-admin) Changes

- `/login` 페이지 신규 (이메일+비밀번호 폼)
- `AuthStore` (현재 사용자, role, 로딩 상태)
- `ProtectedRoute` 컴포넌트 (미인증 시 `/login` 리디렉션)
- `api-client`: `credentials: 'include'` 추가
- 401 인터셉터 → 자동 로그아웃 + `/login` 리디렉션
- 슈퍼어드민 전용 메뉴 (어드민 관리)는 `role === 'SUPER_ADMIN'`일 때만 노출

## Dependencies

**선행 작업 (blocker)**:
- **Flyway 인프라 도입** (`chore/flyway-migration` 브랜치, 별도 진행)
  - 이유: 본 설계의 스키마 재설계는 Flyway 기반 버전 관리 위에서 실행해야 추적/재현 가능
  - Flyway 머지 후 `feature/admin-auth` 브랜치에서 본 설계 실행

## Open Decisions (향후 세부 설계에서 확정)

| 주제 | 결정 필요 항목 |
|---|---|
| IAM 스키마 | UserAccount 컬럼 상세, `USER_ACCOUNT` 테이블 재구성 방식, `user_type` discriminator 제거 전략 |
| Administration 스키마 | AdminGrant를 Administrator 엔티티의 필드로 흡수할지, 별 엔티티로 둘지 (감사 이력 필요 여부) |
| JWT 클레임 | 최소 클레임 vs 풍부 클레임 (DB 조회 비용 vs 토큰 크기), authorityTier/memberId 포함 여부 |
| 슈퍼어드민 시딩 | env var 이름 확정, 비밀번호 로테이션 워크플로우 |
| 어드민 CRUD API | 응답 schema, 비밀번호 재발급 플로우, 삭제 시 soft delete 여부 |
| 프런트 인증 상태 | Zustand store 구조, 401 재시도 전략, 로그인 유지/자동 만료 |
| `ProviderType.ADMIN` 정리 | rename vs 유지 결정 |

## References

- 백엔드 코드:
  - [SecurityConfig.java](../../../common/src/main/java/com/pfplaybackend/api/common/config/security/SecurityConfig.java)
  - [UserAccountData.java](../../../user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/UserAccountData.java)
  - [MemberData.java](../../../user/src/main/java/com/pfplaybackend/api/user/domain/entity/data/MemberData.java)
  - [ProviderType.java](../../../common/src/main/java/com/pfplaybackend/api/common/config/security/enums/ProviderType.java)
  - [AccessLevel.java](../../../common/src/main/java/com/pfplaybackend/api/common/config/security/enums/AccessLevel.java)
- 관련 사전 작업 문서:
  - [Flyway Migration Design](./2026-03-30-flyway-migration-design.md)
  - [Flyway Migration Plan](../plans/2026-03-30-flyway-migration.md)
