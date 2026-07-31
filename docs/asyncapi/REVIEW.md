# PFPlay WebSocket 이벤트 설계 검수 리포트

> ⚠️ **2026-03-09 시점 검수 리포트다** (확인: 2026-07-31). 지적 사항의 반영 여부는 개별 확인이
> 필요하다. 스펙 본문 `asyncapi.yml` 은 2026-07-31 에 갱신됐다(시스템 공지 채널·룸 조치 이벤트 추가).

> **검수 기준**: AsyncAPI 3.0 Best Practices, CloudEvents v1.0 Spec, STOMP Protocol Convention, 실무 이벤트 설계 가이드
> **작성일**: 2026-03-09

---

## 1. 현황 요약

| 항목 | 현재 상태 |
|------|----------|
| 프로토콜 | STOMP over WebSocket |
| 서버→클라이언트 채널 | 1개 (파티룸 브로드캐스트) + 1개 (하트비트) |
| 이벤트 종류 | 11개 (브로드캐스트) + 1개 (하트비트) |
| 클라이언트→서버 | 2개 (채팅 전송, 하트비트) |
| 메시지 포맷 | JSON (Java record 직렬화) |
| 이벤트 식별 | `eventType` 필드 (snake_case enum) |

---

## 2. 잘 된 점 (Strengths)

### 2-1. 단일 채널 + eventType 패턴 ✅
모든 파티룸 이벤트가 `/sub/partyrooms/{partyroomId}` 하나로 통합되어 있고, `eventType`으로 구분합니다.
- **장점**: 클라이언트가 구독 1개만 관리하면 됨, 구독/해제 오버헤드 최소화
- **업계 사례**: Discord, Slack도 단일 WebSocket 연결에 `type` 필드로 이벤트 구분

### 2-2. 불변 record 메시지 ✅
모든 메시지가 Java `record`로 구현되어 불변성이 보장됩니다.
- 스레드 안전, 직렬화 일관성

### 2-3. Redis Pub/Sub을 통한 분산 브로드캐스트 ✅
멀티 인스턴스 환경에서 Redis를 메시지 버스로 사용하여 모든 인스턴스에 이벤트가 전파됩니다.
- 수평 확장 가능한 설계

### 2-4. 도메인 이벤트 → Redis → STOMP 분리 ✅
`DomainEventRedisRelay`를 통해 도메인 이벤트와 메시지 전송이 깔끔하게 분리되어 있습니다.

### 2-5. GroupBroadcastMessage 인터페이스 ✅
모든 브로드캐스트 메시지가 `GroupBroadcastMessage` 인터페이스를 구현하여 타입 안전합니다.

---

## 3. 개선 필요 사항 (Issues)

### 이슈 #1: 이벤트 네이밍 일관성 부족 ⚠️

**현재 상태**: snake_case이나 명명 패턴이 불균일합니다.

| eventType | 패턴 | 문제점 |
|-----------|------|--------|
| `chat` | 명사 | 동작 불명확 (전송? 수신? 삭제?) |
| `partyroom_access` | 리소스_동작 | `accessType`에 ENTER/EXIT가 있어 이벤트 자체가 모호 |
| `partyroom_deactivation` | 리소스_상태변화 | OK |
| `partyroom_closed` | 리소스_과거분사 | `deactivation`은 명사, `closed`는 형용사 — 불일치 |
| `playback_start` | 리소스_동사원형 | `closed`는 과거분사인데 `start`는 원형 — 불일치 |
| `reaction_motion` | 리소스_명사 | "모션이 발생했다"는 의미가 불명확 |
| `reaction_aggregation` | 리소스_명사 | "집계가 변경되었다"는 의미가 불명확 |
| `crew_grade` | 리소스_속성 | "등급이 어떻게 됐다"는 동작 정보 없음 |
| `crew_penalty` | 리소스_속성 | 위와 동일 |
| `crew_profile` | 리소스_속성 | 위와 동일 |
| `dj_queue_change` | 리소스_동사 | 유일하게 동사(`change`)가 포함됨 — 불일치 |

**권장 패턴**: `{domain}.{resource}.{past_tense_verb}` (CloudEvents `type` 속성 권장)

```
예시:
  chat                    → chat.message.sent
  partyroom_access        → partyroom.crew.entered / partyroom.crew.exited (분리)
  partyroom_deactivation  → partyroom.playback.deactivated
  partyroom_closed        → partyroom.closed
  playback_start          → partyroom.playback.started
  reaction_motion         → partyroom.reaction.performed
  reaction_aggregation    → partyroom.reaction.aggregated
  crew_grade              → partyroom.crew.grade_changed
  crew_penalty            → partyroom.crew.penalized
  crew_profile            → partyroom.crew.profile_changed
  dj_queue_change         → partyroom.dj_queue.changed
```

**판단**: 현재 이벤트 이름이 이미 프론트엔드에서 사용 중이라면, **네이밍 변경은 Breaking Change**입니다. 개선 대비 비용이 크므로 **현재 단계에서는 문서에 명확한 설명을 추가**하되, v2 전환 시 정리하는 것이 현실적입니다.

**심각도**: 🟡 Medium (기능 문제 아닌 가독성/일관성 이슈)

---

### 이슈 #2: `partyroom_access` 이벤트가 ENTER/EXIT를 합쳐서 전달 ⚠️

**현재**: 하나의 `partyroom_access` 이벤트에 `accessType: ENTER | EXIT`로 구분합니다.

**문제점**:
- 클라이언트에서 항상 `if (accessType === 'ENTER') ... else ...` 분기 필요
- ENTER 시에는 `CrewSummary`(12개 필드) 전체가 필요하지만, EXIT 시에는 `crewId`만 필요 → 불필요한 데이터 전송

**권장**: 이벤트를 분리하거나, 최소한 EXIT 시 페이로드를 축소

```
옵션 A: 이벤트 분리
  partyroom.crew.entered  (crew: CrewSummary 전체)
  partyroom.crew.exited   (crewId만)

옵션 B: 현행 유지 + 페이로드 최적화
  partyroom_access (ENTER) → crew: CrewSummary 전체
  partyroom_access (EXIT)  → crew: { crewId: long } 만
```

**심각도**: 🟡 Medium

---

### 이슈 #3: 메시지 메타데이터 부족 ⚠️

**현재**: 메시지에 `partyroomId`와 `eventType`만 공통으로 포함됩니다.

**CloudEvents 필수 속성 대비 누락**:

| CloudEvents 속성 | 현재 상태 | 필요성 |
|------------------|----------|--------|
| `id` (이벤트 고유 ID) | ❌ 없음 | 중복 방지, 디버깅, 멱등성 처리에 필수 |
| `source` (발생 출처) | ❌ 없음 | 멀티 인스턴스에서 어디서 발생했는지 추적 |
| `time` (타임스탬프) | ❌ 없음 (chat만 messageId에 millis 포함) | 이벤트 순서 보장, 디버깅 |
| `specversion` | ❌ 없음 | 버전 호환성 |

**권장**: 최소한 `id`와 `timestamp`를 공통 필드로 추가

```json
{
  "id": "evt-abc-123",
  "eventType": "playback_start",
  "timestamp": 1709964213000,
  "partyroomId": { "id": 10 },
  ...
}
```

**구현 방법**: `GroupBroadcastMessage` 인터페이스에 `default` 메서드 추가 또는 메시지 래퍼 적용

**심각도**: 🟠 Medium-High (디버깅, 메시지 유실 감지에 영향)

---

### 이슈 #4: `partyroomId`가 객체 형태 `{ "id": 10 }` ⚠️

**현재**: Value Object `PartyroomId`가 그대로 직렬화되어 `{ "id": 10 }` 형태입니다.

**문제점**:
- 클라이언트에서 `message.partyroomId.id`로 2단계 접근 필요 (불편)
- 다른 ID 필드(`crewId`, `djId`)는 `long` 원시값인데 `partyroomId`만 객체 → 불일치
- JSON 페이로드 크기 증가 (이벤트당 약 15바이트, 초당 수백 이벤트면 유의미)

**권장**: 직렬화 시 flat한 `long`으로 변환

```json
// Before
{ "partyroomId": { "id": 10 }, "crewId": 42 }

// After
{ "partyroomId": 10, "crewId": 42 }
```

**구현 방법**: `PartyroomId`에 `@JsonValue` 어노테이션 추가, 또는 WebSocket 전용 직렬화 설정

**주의**: REST API에서도 `PartyroomId`를 사용하므로, WebSocket 전용 직렬화를 분리하거나 전역 `@JsonValue`의 영향을 검토해야 합니다.

**심각도**: 🟡 Medium

---

### 이슈 #5: DJ 큐 변경 시 전체 목록 전송 ⚠️

**현재**: `dj_queue_change` 이벤트가 DJ 큐 **전체 목록**(`List<DjWithProfileDto>`)을 매번 전송합니다.

**문제점**:
- DJ 1명 추가/제거 시에도 전체 큐(N명)를 전송 → 큐가 길면 비효율
- 대역폭 낭비 (특히 모바일 환경)

**권장**: delta(차분) 방식 도입

```json
// 옵션 A: 액션 기반
{ "action": "ENQUEUED", "dj": { "crewId": 42, ... }, "position": 3 }
{ "action": "DEQUEUED", "djCrewId": 42 }

// 옵션 B: 현행 유지 (단순성 우선)
// 큐 크기가 보통 5~10명이면 전체 전송도 합리적
```

**판단**: 파티룸 DJ 큐가 보통 10명 이하라면 현재 방식도 실용적입니다. 큐가 커질 가능성이 없다면 **현행 유지 합리적**.

**심각도**: 🟢 Low (현재 규모에서는 문제 없음)

---

### 이슈 #6: `CrewProfileMessage`와 `CrewSummary`의 필드 중복 ⚠️

**현재**: 아바타 관련 필드가 `CrewProfileMessage`(13필드)와 `CrewSummary`(12필드)에 거의 동일하게 중복됩니다.

```
공통 필드: crewId, nickname, avatarCompositionType, avatarBodyUri, avatarFaceUri,
           avatarIconUri, combinePositionX, combinePositionY, offsetX, offsetY, scale
```

**문제점**:
- 아바타 구조 변경 시 2곳 동시 수정 필요
- AsyncAPI 스키마에서도 거의 동일한 스키마가 2개

**권장**: 공통 `AvatarProfile` 스키마를 추출하여 재사용

```java
public record AvatarProfile(
    AvatarCompositionType avatarCompositionType,
    String avatarBodyUri, String avatarFaceUri, String avatarIconUri,
    int combinePositionX, int combinePositionY,
    double offsetX, double offsetY, double scale
) {}
```

**심각도**: 🟢 Low (코드 정리 수준)

---

### 이슈 #7: 미사용 MessageTopic enum 값 존재 ℹ️

**현재**: `MessageTopic` enum에 `PARTYROOM_NOTICE`와 `PLAYBACK_SKIP`이 정의되어 있으나 실제 발행/구독에서 사용되지 않습니다.

**권장**: 미사용 enum 값을 제거하거나 `@Deprecated` 표시

**심각도**: 🟢 Low

---

## 4. 종합 평가

| 평가 항목 | 점수 | 코멘트 |
|----------|------|--------|
| **채널 설계** | ⭐⭐⭐⭐⭐ | 단일 채널 + eventType 패턴은 모범 사례 |
| **메시지 구조** | ⭐⭐⭐⭐ | record 기반, 타입 안전. 메타데이터(id, timestamp) 부족 |
| **이벤트 네이밍** | ⭐⭐⭐ | snake_case 통일은 OK, 시제/품사 일관성 부족 |
| **페이로드 효율** | ⭐⭐⭐⭐ | 대부분 적절. partyroomId 객체 오버헤드만 아쉬움 |
| **확장성** | ⭐⭐⭐⭐⭐ | Redis Pub/Sub 기반 분산 아키텍처 우수 |
| **문서화** | ⭐⭐⭐⭐⭐ | AsyncAPI 3.0 스펙 + HTML 문서 생성 완료 |

**종합**: 4.2 / 5.0 — 실무적으로 잘 설계된 시스템. 핵심 개선 포인트는 **메시지 메타데이터(id, timestamp) 추가**와 **partyroomId flat 직렬화**.

---

## 5. 우선순위별 개선 로드맵

### Phase 1: 비파괴적 개선 (Breaking Change 없음)
1. ✅ AsyncAPI 스펙 문서화 (완료)
2. 🔧 메시지에 `id` + `timestamp` 공통 필드 추가 (신규 필드 추가는 backward compatible)
3. 🔧 미사용 `MessageTopic` enum 값 정리

### Phase 2: 검토 후 진행 (Minor Breaking Change)
4. 🔧 `partyroomId` 직렬화를 `{ "id": 10 }` → `10`으로 변경 (프론트 동시 수정)
5. 🔧 `partyroom_access` EXIT 시 페이로드 축소

### Phase 3: v2 전환 시 (Major Breaking Change)
6. 🔧 이벤트 네이밍 체계 통일 (`domain.resource.past_verb`)
7. 🔧 `partyroom_access`를 `entered`/`exited` 이벤트로 분리
8. 🔧 `AvatarProfile` 공통 스키마 추출

---

## 참고 자료

- [AsyncAPI 3.0 Specification](https://www.asyncapi.com/docs/reference/specification/v3.0.0)
- [CloudEvents WebSocket Protocol Binding](https://github.com/cloudevents/spec/blob/main/cloudevents/bindings/websockets-protocol-binding.md)
- [CloudEvents Spec](https://github.com/cloudevents/spec/blob/main/cloudevents/spec.md)
- [AsyncAPI, CloudEvents, OpenTelemetry 비교](https://www.asyncapi.com/blog/async_standards_compare)
