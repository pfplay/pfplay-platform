# ADR-011: 어드민 Origin Guard

## Status
Accepted (현재 구현) — **Hardening Pending**

> **작성일**: 2026-04-20
> **관련 코드**: `common/.../config/security/web/properties/AdminOriginProperties`, `common/.../config/security/SecurityConfig`
> **동반 ADR**: [ADR-006](006-admin-csrf-token.md) (Option A — 토큰), 본 ADR이 Option B (origin allowlist)

## Context

어드민 API(`/api/v1/admin/**`, `/api/v1/auth/admin/**`)는 사용자 측 API보다 권한이 높고, 별도 서브도메인(`admin.pfplay.xyz`)에서 동작한다. 토큰 기반 CSRF 방어(ADR-006)는 same-origin spoofing은 잘 막지만, CORS 레이어가 잘못 구성되었거나 브라우저 버그가 있어 cross-origin 공격 벡터를 갖는 공격자를 멈추지는 못한다.

저렴한 두 번째 방어선이 `Origin` / `Referer` 헤더 allowlist다 — 알려진 어드민 프론트엔드의 `Origin`이 아니면 어드민 경로 요청을 거부한다. Spec §5.4.3은 A + B를 defense in depth로 권장한다.

## Decision

어드민 origin 목록을 `application.yml`의 `pfplay.security.admin-origin-guard.allowed`에 두고, `AdminOriginProperties`로 바인딩한 뒤 `SecurityConfig`에서 어드민 경로의 상태 변경 요청에 강제한다.

### 강제 범위
- HTTP 메서드 ∈ {POST, PUT, PATCH, DELETE}
- 경로가 `/api/v1/admin/**` 또는 `/api/v1/auth/admin/**`
- `Origin` 헤더(없으면 `Referer`)가 설정된 origin 중 하나와 일치해야 함; 불일치 → 403

### Allowlist
현재 `application.yml` 스니펫 (모든 비-로컬 프로파일 공유):
```yaml
pfplay:
  security:
    admin-origin-guard:
      allowed:
        - https://admin.pfplay.xyz
        # ... 추가 어드민 프론트엔드
```

로컬은 `application-local.yml` / `application-test.yml`에서 완화된 값 사용.

## Consequences

### Positive
- ADR-006과 함께 defense in depth: 유출된 CSRF 토큰도 일치하는 어드민 origin 없이는 무용
- 새 어드민 프론트엔드(미리보기 배포, 대체 호스트명)는 **백엔드 재배포가 필요**하여 추가 허들로 작동 — 의도된 트레이드오프

### Negative — Hardening Pending
현재 구현에 두 가지 한계가 있고, outstanding으로 추적 중:

1. **환경 간 단일 리스트** — `application.yml`이 모든 비-로컬 프로파일이 공유하는 하나의 allowlist를 보유. dev / stg / prod 어드민 프론트엔드는 개념적으로 다른 origin인데 현재는 한 리스트에 공존. 최소권한 원칙에 어긋남 (dev 어드민 origin이 prod 어드민 요청을 authorize 하면 안 됨)
2. **소스에 하드코딩** — 새 어드민 origin 추가는 코드 commit + 배포가 필요. 미리보기 환경(Cloudflare Pages branch URL)에는 마찰 지점

Hardening 경로: 프로파일별 분리(`application-dev.yml`, `application-stg.yml`, `application-prod.yml`)로 활성 Spring profile에 따라 resolve. `docs/OPERATIONS.md` §4 참고.

## 채택하지 않은 대안

- **Wildcard origin** — 목적을 무효화
- **DB 저장 allowlist** — 모든 어드민 호출마다 runtime SQL 의존이 추가됨; 1인 운영 템포 기준 trade-off가 합당하지 않음
- **CORS 단독 방어** — CORS는 브라우저의 cross-origin **read**를 막을 뿐 cross-origin **write**를 막지 못함 → 본 guard를 대체할 수 없음

## Verification

- `AdminAuthControllerTest`: 허용 origin → 200; 비허용 origin → 403; `Origin` 누락 + 유효 `Referer` → 200
- 프로덕션에서는 어드민 콘솔이 `/api/v1/admin/**`을 호출할 때마다 가드가 실행됨
