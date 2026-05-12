# pfplay-platform 운영 메모

코드만 봐서는 의도를 알기 어려운 결정·함정 모음입니다. **변경 제안 전 필독.**

## Table of Contents

1. [JVM TZ KST 고정 정책](#1-jvm-tz-kst-고정-정책)
2. [Flyway 정책](#2-flyway-정책)
3. [Super-admin V5 placeholder seed](#3-super-admin-v5-placeholder-seed)
4. [admin-origin-guard](#4-admin-origin-guard)
5. [Cookie 도메인 분리 (admin ↔ shared)](#5-cookie-도메인-분리-admin--shared)
6. [CSRF + OAuth2 wrapping workaround](#6-csrf--oauth2-wrapping-workaround)
7. [Combinable body `icon_uri = NULL`은 정상](#7-combinable-body-icon_uri--null은-정상)
8. [첫 DJ 등록 직후 silent deactivate](#8-첫-dj-등록-직후-silent-deactivate)
9. [V13 / V14 / V15 schema 정책](#9-v13--v14--v15-schema-정책)
10. [dev/stg DB reset 정책](#10-devstg-db-reset-정책)
11. [JDK 21 빌드 환경 (Windows)](#11-jdk-21-빌드-환경-windows)
12. [PR 13~14g 묶음 — admin 도메인 진입 이력](#12-pr-1314g-묶음--admin-도메인-진입-이력)

---

## 1. JVM TZ KST 고정 정책

### 정책
모든 환경(JVM 컨테이너 + 호스트)에서 시간대를 `Asia/Seoul`로 고정합니다. 설정 위치 2곳:
- **`app/Dockerfile`**: `ENV TZ=Asia/Seoul`
- **`ClockConfig`**: 애플리케이션 단에서 명시적 `Clock` Bean (JVM 기본 UTC를 덮음)

### 이유
- 사용자가 어떤 timezone에서 접속하든 admin/사용자 콘솔 모두 KST로 표시
- 백엔드 로그·DB·이벤트 시간이 단일 기준 (혼선 방지)
- 단일 운영 지역(KR)이라 멀티 TZ 지원은 YAGNI

### 적용 이력
- PR #202 (develop) / #203 (release) / #205 (main)으로 ship
- dev/stg는 DB reset으로 정착
- prod도 admin 콘솔 prod 진입 시점에 기존 가정이 깨질 수 있어 SQL 마이그레이션 옵션을 보유

### 프론트 영향
- pfplay-admin: `shared/lib/format-kst.ts`로 KST 표시
- pfplay-web: 동일 가정. 사용자의 로컬 TZ를 따르지 않습니다

## 2. Flyway 정책

### `out-of-order=false`
- 신규 마이그레이션은 항상 **최신 버전 뒤에 append**
- out-of-order를 허용하면 PR 간 충돌 시 운영 환경에서 누락 위험

### 슬롯 점프 회복 패턴
PR이 머지 순서대로 들어오지 않으면 마이그레이션 버전 슬롯이 점프할 수 있습니다 (예: `V12` 다음 바로 `V14`).

회복 절차:
1. 슬롯 점프를 발견하면 **history rewrite**로 버전 번호를 정렬 (커밋되지 않은 마이그레이션이거나, 머지 전 PR이어야 함)
2. dev/stg는 DB reset으로 클린 적용
3. prod 진입 시점에는 이미 정렬된 시퀀스만 들어가야 함
4. 정책: `out-of-order=false` 유지 (재정렬이 회복 경로)

### 위치
- 파일: `app/src/main/resources/db/migration/V{버전}__{설명}.sql`
- 환경별 설정: `app/src/main/resources/application*.yml`

## 3. Super-admin V5 placeholder seed

### 시드 라이프사이클
1. **V5 placeholder migration**이 super-admin row의 자리만 잡아둡니다 (실제 값 없음)
2. 부팅 시 `ApplicationReadyEventListener`가 환경변수로 받은 seed로 super-admin을 idempotent하게 생성 (`matchIfMissing` 가드)
3. `SuperAdminSeedService`는 시드된 super-admin이 이미 있으면 부팅 시 더 이상 시드를 만들지 않습니다

### `ADMIN_SEED_*` 제거 권장
- 처음 prod 진입에서만 env가 필요합니다
- 첫 부팅 후 secret을 deploy env에서 제거하는 게 권장 경로 (secret hygiene)
- **현재 outstanding action**: prod에서 `ADMIN_SEED_*` 제거 (admin 콘솔 prod 진입 후 미수행)

### 로컬
- `docker-compose.local.yml` + `.env.local` 패턴은 항상 시드를 수행합니다
- 시드된 계정: `admin@pfplay.local` / `local-test-only-rotate-in-prod` (rotation 불필요)

## 4. admin-origin-guard

### 현재 상태
- `application.yml`에 `admin-origin-guard.allowed`가 하드코딩되어 있음
- **env 분리되어 있지 않음** (dev/stg/prod 각각의 admin 도메인을 다르게 다룰 수 없음)

### Pending hardening
- env-aware한 origin 리스트 분리가 필요합니다
- 새 admin 도메인을 띄울 때는 `application.yml`에 직접 추가하는 임시 운영 절차
- **검증 outstanding**: dev/stg admin 도메인이 현재 `application.yml` 리스트에 들어 있는지 확인 필요

### 위험
- prod admin 도메인이 누락되면 admin 콘솔이 prod에서 차단됩니다
- 신규 admin 도메인 추가 시 반드시 `application.yml` 갱신

## 5. Cookie 도메인 분리 (admin ↔ shared)

### 정책
- `ADMIN_COOKIE_DOMAIN=admin.pfplay.xyz` — SameSite=**Strict**
- shared `COOKIE_DOMAIN=.pfplay.xyz` — SameSite=**Lax**

### 이유
- admin 쿠키가 일반 사용자 도메인으로 새지 않도록 **물리적 분리**
- admin은 super-admin/관리자 권한이라 token 노출 위험이 더 크므로 Strict가 적절

### 통일 제안 거절
- "어차피 같은 사용자니까 통일하자"는 제안이 정기적으로 나옵니다
- **거절**. 통일은 보안 경계를 약화시킵니다

## 6. CSRF + OAuth2 wrapping workaround

### 문제
- Spring Security의 OAuth2 client + cookie bearer 조합에서는 CSRF 보호가 framework 단에서 **자동 무력화**되는 알려진 이슈가 있음
- upstream:
  - https://github.com/spring-projects/spring-security/issues/17959
  - https://github.com/spring-projects/spring-security/issues/8668

### 우회
- `SecurityConfig` post-processor에서 `setRequireCsrfProtectionMatcher`를 **다시 호출**
- framework wrapping을 override하여 CSRF 보호를 강제 복구

### Upstream fix 시
- upstream에서 해당 케이스를 처리하면 backend 측 workaround와 함께 제거 가능
- 주기적으로 upstream 진척 확인 권장

### 클라이언트 측 동작
- `XSRF-TOKEN` cookie 발급 → 클라이언트가 `X-XSRF-TOKEN` header로 echo
- pfplay-admin / pfplay-web 모두 unsafe method(POST/PUT/PATCH/DELETE)에 echo

## 7. Combinable body `icon_uri = NULL`은 정상

### 배경
- 아바타 카탈로그 도메인은 body / face / combinable / icons 등 resource type을 가짐
- `combinable` type의 body는 **face와 합성되어야 채팅 아이콘이 결정**되는 구조
- 따라서 combinable body는 **자체 `icon_uri`를 가지지 않습니다** (`NULL`이 정상)

### Admin UI
- 빈 셀로 표시됩니다. "버그처럼 보이지만 의도된 동작"

### DB 제약
- `icon_uri`에 **`NOT NULL` 제약을 추가하지 마세요**
- 다른 type(body, face 등)의 `icon_uri`는 채워져 있지만, combinable은 NULL이 정답

## 8. 첫 DJ 등록 직후 silent deactivate

### 패턴
- `enqueueDj` 직후 `PLAYBACK_DEACTIVATED` 이벤트 + 빈 `djs` 배열 응답을 받는 경우가 있음
- **원인**: 첫 트랙 길이가 파티룸 `playbackTimeLimit`를 초과 → 즉시 종료

### 의도
- 백엔드의 의도된 동작
- 너무 긴 트랙으로 다른 사용자의 DJ 기회를 막는 것을 방지

### 프론트 책임
- `DjQueueChangedEvent` payload는 **AFTER_COMMIT 시점에 late binding** (query 시점 stale 가능)
- 사용자 친화적인 UX 메시지로 해석할 책임은 frontend에 있음 (pfplay-web)

## 9. V13 / V14 / V15 schema 정책

### V13 — 신고 시스템
- `partyroom_report` 테이블 도입
- 파티룸 단위 신고 처리. admin 콘솔에서 후속 조치
- 관련 PR: #12, #13

### V14 — 시스템 공지
- `system_announcement` + 1분 cron 배포
- admin 콘솔에서 등록, 1분 내 사용자 콘솔에 반영
- 토큰 주입 방식: **GCE VM + DOT_ENV append** (Cloud Run 아님)
- β 토글은 Vercel Edge Config 사용 (frontend 측)

### V15 — temp user 호환
- 임시 유저 컬럼 추가, V14 schema와 호환 유지
- 풀 멤버 전환 흐름: `/temporary/full-member`류 임시 엔드포인트
- **prod 가드 필수**: prod에서 임시 엔드포인트(`/temporary/full-member`)가 노출되면 안 됨 → 차단 가드 필요
- **검증 outstanding**: prod ship 후 `/temporary/full-member` → 404 확인 필요
- 관련 PR: develop #196, release #198, pfplay-web#282(CLOSED)

## 10. dev/stg DB reset 정책

- **dev/stg**는 schema breaking change 시 reset 허용 (개발 효율 우선)
- **prod**는 reset 금지. 데이터 보존 마이그레이션 필수
- JVM TZ KST 전환 시 dev/stg는 reset을 사용한 경험이 있고, prod는 SQL 마이그레이션 옵션을 보유했습니다 (admin 콘솔 prod 진입으로 기존 가정이 깨질 수 있어서)

## 11. JDK 21 빌드 환경 (Windows)

### `JAVA_HOME` prefix 필수
시스템 PATH에 다른 JDK가 잡혀 있을 수 있어, Gradle 호출 시 환경변수를 명시합니다.

**PowerShell:**
```powershell
$env:JAVA_HOME = "C:/Users/Eisen/.jdks/ms-21.0.7"
./gradlew :app:bootRun
```

**Bash:**
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:bootRun
```

### 권장 JDK
- Microsoft JDK 21 (`ms-21.0.7` 검증됨)
- Eclipse Temurin 21도 호환

### IDE
- IntelliJ Project SDK도 동일하게 JDK 21로 맞춥니다
- Gradle JVM 설정도 동일

## 12. PR 13~14g 묶음 — admin 도메인 진입 이력

### 개발 단계 (2026-04-29 종료)
- **M5**: PR #12 ~ #13 — 신고 시스템 (V13)
- **M6**: PR #14a ~ #14g — admin 콘솔 묶음
  - 14a: 로그인 + 보호 + AuthStore + `mustChange`
  - 14b: 회원/파티룸 read-only
  - 14c: 단건 mutation 7개
  - 14d: bulk-action
  - 14e, 14f: 후속 polish
  - 14g: 묶음 α 마무리 (310/310 PASS)

### Prod ship (2026-05-09)
- pfplay-platform #205 + pfplay-admin #4 + pfplay-web #288 묶음으로 **admin 콘솔 첫 prod 진입**
- 직후 PR #196 / #198 머지 (V15 temp user 호환 + prod 가드)
- B1 PR #285 — super-admin Amplitude opt-out (pfplay-web)

### Outstanding actions
- `ADMIN_SEED_*` env 제거 (super-admin 시드 후 hygiene) — §3
- admin-origin-guard env-aware 분리 — §4
- prod `/temporary/full-member` 차단 가드 검증 — §9
