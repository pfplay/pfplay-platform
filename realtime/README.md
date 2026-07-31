# Realtime Module — WebSocket Infrastructure

> 실사 기준: 2026-07-31 (`origin/develop`).

## 성격

WebSocket(STOMP) 통신 인프라를 제공하는 **기술 모듈**(BC 아님). 도메인 코드에 대한 의존이
**전혀 없고**, 포트 인터페이스만 정의해 다른 모듈이 구현체를 제공한다.

```
realtime → common   (이게 전부다)
```

## STOMP 구성 (`WebSocketConfig`)

| 항목 | 값 |
|---|---|
| 엔드포인트 | `/ws` (`setAllowedOriginPatterns("*")`) |
| 애플리케이션 prefix | `/pub` |
| 브로커 prefix | `/sub` (simple broker) |
| user destination prefix | `/user` |
| 하트비트 | 전용 `ThreadPoolTaskScheduler`(`stomp-heartbeat-`)로 서버↔클라 양방향 |

Principal 은 핸드셰이크 attribute 의 `uid` 로 정해진다
(`determineUser` → `attributes.get("uid")`). 즉 **STOMP principal name == `String.valueOf(uid)`** 이며,
presence liveness 판정이 이 표현에 의존한다.

> **인증은 핸드셰이크 1회뿐이다.** `configureClientInboundChannel` 은 MDC 인터셉터만 등록하고
> 만료 재검증을 하지 않는다 — 세션이 맺힌 뒤 토큰이 만료돼도 소켓은 계속 산다
> ([#306](https://github.com/pfplay/pfplay-platform/issues/306)).

## 제공하는 Port

| Port | 용도 | 구현체 |
|---|---|---|
| `WebSocketAuthPort` | 핸드셰이크 JWT 인증 | `app/bootstrap` — `JwtWebSocketAuthAdapter` |
| `SessionCachePort` | WS 세션 캐시 라이프사이클 | `app` (party) |
| `SessionRegistryPort` | 사용자 세션 레지스트리 | `app` (party) |
| `PresencePort` | presence 연동 | `app` (party) |

## 소비하는 외부 Port

없음 (zero domain imports).

## 구성 요소

| 파일 | 역할 |
|---|---|
| `config/WebSocketConfig.java` | STOMP 엔드포인트·브로커·하트비트 설정 |
| `interceptor/WebSocketHandshakeInterceptor.java` | 핸드셰이크 시 `WebSocketAuthPort` 로 인증, `uid` 확정 |
| `interceptor/WebSocketMdcChannelInterceptor.java` | inbound 프레임 처리 구간의 MDC 전파 |
| `event/ConnectionEventListener.java` | CONNECT — 세션 등록 |
| `event/DisconnectionEventListener.java` | DISCONNECT — 세션 해제 (유실 가능 — 아래 주의) |
| `event/SubscriptionEventListener.java` | SUBSCRIBE — 구독 처리 |
| `event/UnsubscriptionEventListener.java` | UNSUBSCRIBE |
| `controller/HeartbeatController.java` | `/pub/heartbeat` → `/user/sub/heartbeat` 응답 |
| `sender/SimpMessageSender.java` | `sendToGroup(roomId)` → `/sub/partyrooms/{id}`, `sendToOne(user)` → 개인 하트비트 |

> ⚠️ **disconnect 이벤트는 유실될 수 있다.** 인스턴스 급사·silent WS death 시 핸들러가 돌지
> 않으므로, presence 정확성을 이 리스너에 의존해서는 안 된다. 최종 방어선은 party 쪽 liveness
> 스윕이다([ADR 009](../docs/adr/009-presence-liveness-sweep.md)).

## 클라이언트 계약

목적지 목록과 메시지 종류는 [`../docs/asyncapi/asyncapi.yml`](../docs/asyncapi/asyncapi.yml) 가
기계 판독 가능한 원본이다. 요약은 루트 [`README.md`](../README.md) 「WebSocket Contract」 참조.
