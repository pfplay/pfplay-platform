# ADR-007: 시스템 공지 아키텍처 (V14)

## Status
Accepted

> **작성일**: 2026-05-03
> **관련 spec**: `docs/superpowers/specs/2026-05-03-system-announcement-design.md`
> **관련 plan**: `docs/superpowers/plans/2026-05-03-system-announcement.md`
> **관련 마이그레이션**: `V14__create_system_announcement.sql`

## Context

PFPlay는 super-admin이 시스템 전역 공지(기능 안내, 점검 알림, 긴급 공지)를 모든 연결된 클라이언트에게 파티룸 단위 fan-out 없이 전달할 수 있어야 한다. 첫 prod 유스케이스는 예정 점검: 점검 윈도가 시작되면 클라이언트가 read-only/차단 모드로 전환되어야 하며, 백엔드는 어드민의 수동 클릭 없이 정해진 시각에 그 전환을 자동으로 트리거할 수 있어야 한다.

프론트엔드(pfplay-web)는 Vercel에 배포되어 있고, Edge Config 토글은 edge middleware에서 동기적으로 점검 게이트와 β 기능 플래그를 뒤집기에 가장 자연스러운 위치다.

## Decision

시스템 공지를 **Administration BC**(`api.administration.*`) 내부 aggregate로 다루며, MySQL에 영속화하고, system-wide STOMP 토픽으로 broadcast하며, edge 렌더 소비자를 위해 Vercel Edge Config로 반영한다.

### Aggregate & 스키마 (V14)
- Aggregate: `SystemAnnouncement` (`SystemAnnouncementData`)
- 필수: `type` (`SYSTEM_NOTIFICATION` | `MAINTENANCE_NOTICE`), `severity`, ko/en title + message, `sent_at`, `sent_by_administrator_id`
- 선택: `scheduled_start_at` / `scheduled_end_at` (`MAINTENANCE_NOTICE`인 경우 필수), `expires_at`, `cancelled_at` + `cancelled_by_administrator_id`, `maintenance_started_at`

### 서비스
- `SystemAnnouncementCommandService` — 발행 / 취소 (어드민 전용)
- `SystemAnnouncementQueryService` — 활성 공지 조회
- `MaintenanceSchedulerService` — **1분 주기 cron**이 `scheduled_start_at`을 넘어서면 `MaintenanceStartedEvent` 발행; 점검 모드 진입에 사람 손이 필요 없음

### Broadcast
- `AnnouncementBroadcaster` (`adapter/out/event/`)가 세 가지 이벤트 타입을 **`/sub/system/announcements`**(system-wide, 파티룸 단위 아님)로 발행:
  - `ANNOUNCEMENT_PUBLISHED`
  - `ANNOUNCEMENT_CANCELLED`
  - `MAINTENANCE_STARTED`
- AsyncAPI: `docs/asyncapi/asyncapi.yml#channels.systemAnnouncementBroadcast` 참고

### Edge Config 동기화
- `EdgeConfigPort` (administration 도메인) → `VercelEdgeConfigAdapter` (out adapter)
- pfplay-web의 edge middleware가 `system-status.phase === 'ACTIVE'`를 읽어 백엔드 왕복 없이 모든 라우트를 `/maintenance`로 rewrite
- DB는 source of truth, Edge Config는 파생 뷰

### Operations BC 핸드오프
- `MaintenanceModeFilter` (operations 모듈)가 `SystemConfigCache`를 통해 점검 윈도 활성 시 사용자 요청을 short-circuit
- Administration이 스케줄을 소유하고, Operations가 요청 시점 동작을 강제

## Consequences

### Positive
- **DB가 source of truth**, Redis 무관 — 재시작 후 rehydration 로직 불필요
- **Cron 기반 활성화**로 점검 공지를 몇 시간 전에 예약 가능; 활성화 시점에 사람이 필요 없음
- Edge Config 의존이 있더라도 non-edge 클라이언트는 DB polling 경로(`/api/v1/system/announcements/active`)로 fallback 가능

### Negative
- **토큰 주입 운영 절차 필요**: Vercel Edge Config 엔드포인트가 write 토큰을 요구함 → **GCE VM의 DOT_ENV append** 방식으로 주입 (Cloud Run 아님). `docs/OPERATIONS.md` §1, §9 참고
- AsyncAPI는 `systemAnnouncementBroadcast`를 별도 채널로 두어야 함 — 다른 모든 broadcast에 포함되는 `partyroomId` 메타가 없기 때문

## 채택하지 않은 대안

- **Redis pub/sub로 예약 활성화** — 스케줄을 들고 있는 always-on 리스너가 필요; 1분 SLA로 충분한 점검 알림에는 cron이 더 단순
- **파티룸 단위 fan-out** — 의미 추가 없이 STOMP 메시지 수만 파티룸 수만큼 곱해짐
- **WebSocket 전용 전달** — 점검 공지 시 비-WS 페이지(login, settings)에 머문 사용자가 누락됨; Edge Config가 정적 렌더 케이스를 처리

## Verification

- `SystemAnnouncementCommandServiceTest`, `MaintenanceSchedulerServiceTest`, `SystemAnnouncementRepositoryIntegrationTest`, `AnnouncementBroadcasterIT`, `VercelEdgeConfigAdapterTest`, `AdminAnnouncementControllerTest`, `SystemStatusControllerTest`
- 프론트엔드(pfplay-web)는 자체 e2e에서 edge middleware 동작을 검증
