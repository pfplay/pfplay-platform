# Known Issues

> **최종 검토**: 2026-05-13
> 운영 정책·hygiene 사안(예: `ADMIN_SEED_*` 제거, admin-origin-guard env 분리 등)은 이 문서가 아니라 [`OPERATIONS.md`](OPERATIONS.md)에 정리되어 있습니다. 이 문서는 동작상 결함과 미해결 외부 의존 항목만 다룹니다.

## 1. WebSocket 재연결 시 세션 캐시 누락

**상태**: 앱 크래시 수정 완료 / 클라이언트 알림 미구현 (현재까지 진행 변동 없음)

### 현상

서버 재시작 후 클라이언트가 WebSocket 재연결(STOMP SUBSCRIBE)을 시도하면,
세션 캐시가 저장되지 않아 이후 채팅 등 세션 기반 기능이 동작하지 않는다.

### 원인

1. 서버 재시작 → 기존 Crew 입장 기록 정리됨
2. 클라이언트는 입장 API 없이 WebSocket 재연결만 시도
3. `RedisSessionCacheAdapter.saveSessionCache()` → `getActivePartyroomByUserId()` 결과 없음
4. 세션 캐시 미저장 → 채팅 등 후속 기능 실패

### 수정 이력

- **기존**: `NotFoundException` throw → 이벤트 리스너 스레드 전파 → 애플리케이션 크래시
- **현재**: warn 로그 출력 + 조기 return (앱 크래시 방지)

### 미해결 과제 (프론트엔드 협의 필요)

클라이언트에게 세션이 유효하지 않음을 알려주는 방법 결정:

**A. 구독 destination으로 에러 메시지 전송**
- `/sub/partyrooms/{id}`로 `{ type: "SESSION_INVALID" }` 메시지 push
- 클라이언트가 메시지를 받으면 재입장 또는 로비 이동
- 장점: 연결 유지, 클라이언트가 graceful하게 처리 가능

**B. STOMP ERROR 프레임 전송**
- 클라이언트가 ERROR 수신 → 재연결 로직에서 입장 API부터 다시 호출
- 단점: 연결 자체가 끊어짐

### 관련 파일

- `app/.../adapter/out/persistence/RedisSessionCacheAdapter.java` — 세션 캐시 저장 로직
- `realtime/.../event/SubscriptionEventListener.java` — SUBSCRIBE 이벤트 핸들러

### V16 presence와의 관계
이 이슈는 V16(ADR-010)에서 도입한 presence grace window와 **별개**입니다.
- V16은 *"이미 입장한 크루의 일시 끊김"*을 grace window로 다듬음 (`crew.pending_exit_at` + Redis TTL)
- 본 이슈는 *"서버 재시작으로 인해 입장 기록 자체가 사라진 상태"*를 다룸 — presence 라이프사이클 이전에 발생하는 race

## 2. Prod 임시 유저 엔드포인트 노출 여부 미검증

**상태**: 검증 outstanding (코드 가드는 적용 완료, 운영 검증만 남음)

### 현상
`user` 모듈의 `TemporaryUserInitializeService` / `EasyUserManagementController`는 dev/stg 환경 편의용입니다. prod에서 `/api/v1/users/members/sign/temporary/full-member`류 임시 엔드포인트가 노출되면 안 됩니다.

### 가드 메커니즘 (적용 완료)
- `EasyUserManagementController` 클래스에 **`@Profile("!prod")`** (PR #196 commit `9fcc4637`)
- prod profile에서는 controller bean 자체가 생성되지 않아 endpoint 매칭 없음 → 404
- SecurityConfig matcher는 유지하지만 핸들러 부재로 무해

### 액션 (운영 검증)
prod ship 완료(2026-05-09) 이후 다음을 수동 확인 후 본 이슈 종결:
```bash
curl -i -X POST https://api.pfplay.xyz/api/v1/users/members/sign/temporary/full-member
# expect: HTTP/2 404
```

### 관련 파일
- `user/.../adapter/in/web/EasyUserManagementController.java`
- `user/.../application/service/initialize/TemporaryUserInitializeService.java`
- 자세한 운영 컨텍스트: [`OPERATIONS.md`](OPERATIONS.md) §10
