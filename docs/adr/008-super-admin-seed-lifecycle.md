# ADR-008: Super-Admin 시드 라이프사이클

## Status
Accepted

> **작성일**: 2026-04-29
> **관련 마이그레이션**: `V5__create_administrator.sql` (placeholder row)
> **관련 코드**: `app/bootstrap/ApplicationReadyEventListener`, `app/administration/application/service/SuperAdminSeedService`

## Context

어드민 콘솔(pfplay-admin)을 처음 부팅하려면 super-admin 계정이 미리 존재해야 한다. 첫 배포에는 어드민이 0명이며, 두 번째 어드민은 super-admin만이 발급할 수 있다. 두 가지 안티 패턴을 검토하고 모두 기각했다:

1. **super-admin을 Flyway 마이그레이션에 박아넣기** — 소스/마이그레이션 로그에 비밀(또는 알려진 약한 비밀번호)이 박힘. 위생 관점에서 기각
2. **DBA가 out-of-band로 INSERT** — 배포가 수동 SQL 단계와 결합되며, 매니지드 DB에서 안전하게 수행 불가

요구사항: super-admin이 **첫 부팅 시 주입된 secret으로 자동 생성**되고, **이후 부팅마다 안전하게 재실행 가능**해야 함 (중복 INSERT 없음, 회전된 비밀번호 덮어쓰지 않음).

## Decision

Placeholder + 런타임 idempotent seed 패턴을 채택한다.

### Placeholder (Flyway)
- `V5__create_administrator.sql`은 `administrator` 테이블을 생성하되 **레코드는 삽입하지 않는다**. 마이그레이션에 비밀이 없음.

### 런타임 시드 (Spring 애플리케이션 라이프사이클)
- `ApplicationReadyEventListener` (`app.bootstrap`)가 Spring의 `ApplicationReadyEvent`를 수신
- 한 번만 `SuperAdminSeedService.seedIfMissing(seedEmail, seedPassword)` 호출
- `SuperAdminSeedService` 동작:
  - role 기준으로 기존 super-admin 조회
  - **존재하면 즉시 return** (비교·덮어쓰기 없음)
  - **없으면** 주입된 `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD`로 INSERT (`mustChange = true`)

### `matchIfMissing` 가드
- 리스너를 `@ConditionalOnProperty(... matchIfMissing = true)`로 wiring → 기본은 동작, test 같은 프로파일에서 코드 변경 없이 비활성화 가능

### Secret 위생
- `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD` 환경변수는 **첫 부팅 시점에만 필요**
- super-admin이 한 번 생성된 이후로는 env 값이 inert. 운영자는 **secret을 deploy env에서 제거**하는 것이 권장 경로
- 로컬은 `.env.local`이 한 번만 쓰는 throwaway 페어(`admin@pfplay.local` / `local-test-only-rotate-in-prod`)를 보유 — `docs/OPERATIONS.md` §3 참고

## Consequences

### Positive
- **소스/Flyway에 비밀 없음** — 클린 DB에 마이그레이션 재실행 시 자격증명 노출 없음
- **Idempotent** — env가 남아 있어도 populated DB 재실행은 no-op. 재시작 안전
- **첫 로그인 비밀번호 변경 강제**가 기존 `mustChange` 흐름으로 자연스럽게 작동 (어드민 콘솔이 첫 sign-in 후 `/password/change`로 리다이렉트)

### Negative
- **운영 부채**: prod는 첫 배포 이후로도 `ADMIN_SEED_*` env가 남아 있을 수 있음. 제거는 `docs/OPERATIONS.md` §3의 outstanding action으로 추적

## 채택하지 않은 대안

- **환경별 시드 값을 JAR에 번들** — 빌드와 환경이 결합되어 위생 목적 무력화
- **CLI / admin-CLI로 첫 부팅 시드** — 비밀이 노출되는 표면(shell history, terminal multiplexer)이 늘어남; env 방식은 배포 플랫폼의 secret store에 갇혀 있음
- **부팅마다 항상 덮어쓰기** — 운영 중 회전된 비밀번호를 staled env로 인해 재기동 시점에 덮어쓸 위험

## Verification

- `SuperAdminSeedServiceTest`: 빈 DB → INSERT 발생; populated DB → no-op; env 누락 → no-op (예외 없음)
- `IamRepositoryIntegrationTest`: 시드된 administrator row의 `role` + `mustChange` 플래그 검증
