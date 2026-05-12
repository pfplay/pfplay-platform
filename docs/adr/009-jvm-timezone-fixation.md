# ADR-009: JVM Timezone Asia/Seoul 고정

## Status
Accepted — PR #202 (develop) / #203 (release) / #205 (main) 모두 ship 완료

> **작성일**: 2026-04-30

## Context

PFPlay는 단일 시장(한국)에서 운영되며, 사용자·어드민·운영자 모두 `Asia/Seoul` 시간대에 있다. 이전에는 JVM이 호스트 컨테이너의 timezone을 그대로 따라갔고, 그 값이 환경별로 달랐다(로컬은 호스트 OS, GCE VM은 기본 UTC). 결과적으로:

- `LocalDateTime` 컬럼이 쓰는 쪽·읽는 쪽 위치에 따라 시각이 어긋남
- 로그 타임스탬프는 UTC로 찍히는데 로그를 읽는 사람은 KST로 추론
- Cron 스케줄(특히 V14 maintenance scheduler — ADR-007)이 UTC 경계로 트리거되어 직관적인 KST 경계와 어긋남
- 어드민 콘솔이 prod 진입을 앞두고 있는데, 어드민이 점검 윈도를 KST 기준으로 예약하고 백엔드가 동일하게 해석하리라 기대

이 제품에서 multi-timezone 지원은 YAGNI. 나중에 추가하는 비용(per-user TZ + 렌더 시점 변환)은 한정적이지만, drift를 방치하는 비용은 그렇지 않다.

## Decision

JVM timezone을 `Asia/Seoul`로 고정한다. 호스트나 프로파일이 silent하게 override하지 못하도록 **컨테이너 + 애플리케이션 두 레이어에 모두 명시**한다.

### 컨테이너 레이어
- `app/Dockerfile`: `apk add --no-cache tzdata` (Alpine 베이스라 tzdata 미포함 → TZ env만으로는 zoneinfo를 찾지 못함) + `ENV TZ=Asia/Seoul`
- `docker-compose.*.yml`의 MySQL: `--default-time-zone=+09:00`

### 애플리케이션 레이어
- `ClockConfig`가 `Clock.system(ZoneId.of("Asia/Seoul"))` 기반 `Clock` 빈을 노출 + `KST` 상수 공개 (컨테이너 OS TZ와 무관하게 방어적으로 동작)
- 시각을 읽는 코드는 `LocalDateTime.now()` 직접 호출 대신 `Clock`을 DI로 받는다. 직접 호출은 ArchUnit smell.

## Consequences

### Positive
- **모든 환경·테스트에서 단일 "now"** — 라이터/리더 간 drift 0
- **어드민·사용자 콘솔이 KST로 표시** — pfplay-admin `shared/lib/format-kst.ts`, pfplay-web의 KST 표시 기본 동작이 이 가정에 의존
- **Cron 스케줄이 KST에 정렬** — V14 점검 윈도가 KST 03:00에 예약되었다면 TZ 산술 없이 03:00에 발화

### Negative
- **기존 데이터의 TZ 가정**이 이 변경 이전과 다를 수 있음
  - dev/stg는 DB reset으로 정리
  - prod는 admin 콘솔 prod 미진입을 가정해 무영향이었으나, 같은 날 admin 콘솔 prod ship으로 가정이 깨짐 → 어긋남 보고 시 `UPDATE *_at = DATE_ADD(*_at, INTERVAL 9 HOUR) WHERE *_at < '<cutover>'` 형태의 일회성 SQL 보정 옵션 보유
- **향후 multi-TZ 요구사항** 시 `ClockConfig`를 request-scoped clock + per-user TZ로 교체해야 함

### 잔존 위험 (follow-up)
- `LocalDateTime.now()` (Clock 인자 없음) 호출이 일부 남아 있음 → Dockerfile TZ env가 fallback 역할 수행. 점진적으로 `LocalDateTime.now(clock)` 패턴으로 마이그레이션 권장 (27개 파일 audit 후 PR로 분리)

## 채택하지 않은 대안

- **Per-request `Clock` 주입** — 단일 지역 제품에 과도하고, `LocalDateTime`은 이미 wall-clock 의미라 `Instant`가 아닌 이상 default TZ는 어디선가 필요함
- **모든 곳에서 `ZonedDateTime`** — 모든 API 경계로 문제만 이동시킬 뿐 해결 안 함; 결국 default TZ가 필요
- **`Instant` + UTC** — 크로스 리전 제품에는 맞지만, 단일 지역에서는 모든 UI/로그 경계에서 변환 비용을 추가

## Verification

- 컨벤션 / ArchUnit: 주입된 `Clock` 없이 `LocalDateTime.now()` / `LocalDate.now()` 직접 호출은 리뷰에서 지적
- 어드민 콘솔의 "now" KST 표시는 pfplay-admin `format-kst.ts` 테스트에서 검증 (다른 리포)
- 운영 검증: PR #205 ship 이후 새 row의 `created_at`과 로그 타임스탬프가 KST로 기록됨
- 운영 컨텍스트: `docs/OPERATIONS.md` §1, §11
