# ADR-010: Presence Grace Window (V16)

## Status
Accepted

> **작성일**: 2026-05-09
> **관련 spec**: `docs/superpowers/specs/2026-05-09-presence-grace-window-design.md`
> **관련 마이그레이션**: `V16__add_presence.sql`

## Context

기존 presence 모델은 WebSocket 단절을 즉시 `OFFLINE`으로 다뤘다. 실제로 세 가지 문제가 발생:

1. **DJ 깜빡임** — DJ의 Wi-Fi가 2초 동안 끊긴 경우 큐에서 제외되고 다음 DJ가 승격되며, 원 DJ가 재접속하면 listener가 되어 있음. "끊김 → 강등" 경로가 의도적 퇴장과 구분되지 않음
2. **Listener 잡음** — 짧은 탭 서스펜드나 네트워크 로밍이 `crew_exited` / `crew_entered` 페어를 발생시키고, 다른 모든 클라이언트의 아바타 그리드를 재렌더링하게 함
3. **재시작 취약성** — Redis 키만 들고 있던 presence는 앱 재시작에 살아남지 못했고, rolling deploy 시 모든 사용자가 사라진 것처럼 보임

요구사항: (a) 일시적 단절과 진짜 퇴장을 구분, (b) DJ에게 listener보다 긴 유예 부여(방송 책임), (c) 애플리케이션 재시작에도 견딤.

## Decision

Crew presence에 두 가지 상태를 추가하고, Redis TTL이 grace 타이머를 구동하되 DB row가 권위를 가지는 모델을 도입한다.

### 스키마 (V16)
```sql
ALTER TABLE crew
    ADD COLUMN pending_exit_at DATETIME(6) NULL,
    ADD INDEX idx_crew_pending_exit (pending_exit_at);
```

상태 lattice:
- `ONLINE` — 접속 중 (`pending_exit_at` null)
- `PENDING_EXIT` — 최근 단절, grace 진행 중 (`pending_exit_at` 세트, Redis TTL 키 살아 있음)
- `OFFLINE` — grace 만료 후 (기존 exit 처리와 동일; `pending_exit_at` 클리어 + `crew_exited` 발행)

### Source of truth
- **DB row가 권위**. `crew.pending_exit_at`은 Redis flush, 앱 재기동, rolling deploy에 모두 견딤
- **Redis TTL 키가 grace 타이머**. `PresenceExpirationListener`가 Redis keyspace `expired` 이벤트를 받아 `OFFLINE` 확정
- **Cron 안전망**이 주기적으로 `pending_exit_at`을 스캔 → Redis 장애가 PENDING_EXIT 상태로 영원히 stuck시키지 못하게 함

### Grace 초 (system_config)
```sql
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('presence.dj_grace_seconds',       '30', '현재 DJ가 끊겼을 때 OFFLINE 판정까지의 유예(초)'),
    ('presence.listener_grace_seconds', '10', '일반 listener가 끊겼을 때 OFFLINE 판정까지의 유예(초)');
```

DJ 30s, listener 10s. 값은 `system_config`(Operations BC)에 두어 배포 없이 튜닝 가능.

### 컴포넌트
- `realtime.port.PresencePort` — 포트 인터페이스 (도메인 import 0)
- `app.party.adapter.out.realtime.PresencePortAdapter` — Party 측 어댑터
- `app.party.application.service.PartyroomPresenceService` — 상태 전이 orchestrate
- `app.party.adapter.in.listener.PresenceExpirationListener` — Redis keyspace expire 구독자 → `OFFLINE` 확정

### Silent 전이
- `ONLINE → PENDING_EXIT`, `PENDING_EXIT → ONLINE`은 **silent** (STOMP broadcast 없음). 프론트엔드가 transient flicker를 자체 처리하며, 변동 사실은 알려지지 않음
- `PENDING_EXIT → OFFLINE` 확정(또는 직접 exit) 시에만 기존 `crew_exited` 발행. **AsyncAPI에 신규 토픽 추가 불필요**.

## Consequences

### Positive
- DJ가 30초 이내 단절은 큐 위치 보존 → 의도적 퇴장과 구분
- listener 측 broadcast 잡음 대폭 감소 — 짧은 단절은 fan-out 트리거 안 함
- Rolling deploy 시 in-flight `PENDING_EXIT` crew는 cron 안전망이 캐치할 때까지 유지(허용; cron 주기로 bounded)
- 공개 surface 변경 없음 — 클라이언트는 동일한 `crew_exited` 이벤트를 받되 빈도가 줄어듦

### Negative
- 상태 머신이 두 단계 → 한 단계 더 복잡 (다만 격리되어 있어 도메인 다른 영역엔 영향 없음)
- Redis keyspace notification 의존성 (`Ex`) — `docker-compose.local.yml`의 redis `--notify-keyspace-events Ex` 설정 필요

## 채택하지 않은 대안

- **Redis 단독 상태** — 재시작 내구성 요구를 충족하지 못함
- **DB 단독 + 폴링 타이머** — 활성 파티룸 수에 비례한 DB 부하; Redis TTL이 이 용도에 적합
- **Per-crew grace 오버라이드** — 시기상조; 두 역할(DJ vs listener)이 관측된 통증을 모두 커버
- **`PENDING_EXIT` broadcast** — 프론트엔드에 구현 디테일이 새며, silent 회복이 의도된 UX

## Verification

- `PartyroomPresenceServiceTest`, `CrewRepositoryAtomicToggleIT`, `realtime/.../event/`의 리스너 테스트들
- 운영: stg rolling deploy에서 온라인 crew의 spurious `crew_exited` 0건 (이전 실패 모드)
