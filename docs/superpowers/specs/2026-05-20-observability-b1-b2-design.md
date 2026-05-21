# Observability Phase B1 + B2 — JSON structured logging + MDC interceptor 설계

- 작성일: 2026-05-20
- 대상: pfplay-platform `app` + `realtime` + `common` 모듈
- 분류: Observability infrastructure (Phase A 의 단순 로그 호출 위에 격상)
- 심각도: Enabler (Phase A+B+C 까지 가야 "측정 가능" 의 마지노선)
- 선결 결정 (사용자, 2026-05-20):
  1. Masking 범위 = **표준 (secret + PII)** — password/JWT/AdminAccessToken/SharedSessionToken/XSRF/API key + email + IP
  2. MDC field = **표준 4 (`requestId` / `userId` / `sessionId` / `partyroomId`)**, traceId/spanId 및 eventLogicalKey 는 out-of-scope (실측 후 별건)
  3. MDC propagation = **TaskDecorator + 명시 helper 이중 방어**

## 1. 배경 / 문제

Phase A (PR #229/#236/#308) 머지로 critical-path 로그·messageId·RequestId 가 들어왔으나 **출력 format 이 stdout plain text**. Cloud Run/GCE 자동 capture 가 `textPayload` 로 저장. 결과:

- `jsonPayload.requestId="abc"` 같은 indexed query 불가 — `textPayload =~ "requestId=abc"` regex 만 가능 (느림·부정확)
- partyroomId/userId 가 메시지 문자열 안에 박혀 추출 어려움
- MDC 자체가 없어서 한 요청·세션·이벤트 내 cross-component 로그를 묶어 보기 어려움

본 spec 은 Phase B 의 **B1 (JSON encoder) + B2 (MDC interceptor)** 을 통합 설계. B3 (Actuator/Micrometer counter), B4 (sampling/retention ADR), Phase C (Cloud Run logging sink) 는 out-of-scope — *1주 실측 후* 진행 결정 (memory `feedback_observability_stack` 의 2026-05-20 결정).

## 2. 본 phase 의 가치 사이클

- **즉시**: stdout JSON 출력 → Cloud Run/GCE 자동 capture → Cloud Logging 의 jsonPayload 인덱싱 자동 활성
- **이후**: GCP Logs Analytics 에서 `jsonPayload.partyroomId=X AND jsonPayload.message =~ "[doStart]"` 같은 indexed SQL 가능
- **측정 prerequisite**: Phase A plan 의 §"후속: prod 진입 후 1주 실측 갱신" 의 §c (필드별 indexed query) / §d (requestId 단위 trace) / §e (JSON line 크기 실측) 가 본 spec 머지 후에야 가능

## 3. 범위

### 포함
- ✅ `logback-spring.xml` 신규 (app/src/main/resources/)
- ✅ `logstash-logback-encoder` 의존성 (app/build.gradle)
- ✅ Profile 분기: local 콘솔 pattern (가독성), dev/staging/prod JSON encoder
- ✅ Masking: secret (password/JWT/AdminAccessToken/SharedSessionToken/X-XSRF-TOKEN/API key) + PII (email, IP)
- ✅ MDC 표준 4 field: `requestId`, `userId`, `sessionId`, `partyroomId`
- ✅ HTTP: `RequestIdInterceptor` 격상 (ThreadLocal → MDC)
- ✅ WebSocket: 신규 `WebSocketMdcChannelInterceptor`
- ✅ @Async: `userActivityLogExecutor` 에 `MdcTaskDecorator` 설정
- ✅ Domain event: `MdcHelper.scope()` helper + listener 진입부 적용
- ✅ 단위 + 통합 테스트 (회귀 zero 보장)

### 제외 (out-of-scope)
- ❌ Spring Boot Actuator + Micrometer (= B3, 측정 후)
- ❌ Sampling/retention 정책 ADR (= B4, 측정 후 임의 숫자 회피)
- ❌ Cloud Run logging sink (= Phase C)
- ❌ traceId / spanId field (Phase D OpenTelemetry, lived pain 후)
- ❌ eventLogicalKey field (Phase A1 deferred, 실측 후 별건)
- ❌ logback-include-base.xml 분리 (단일 파일로 충분)
- ❌ Cloud Logging side: query template / dashboard / alert (별건)
- ❌ Frontend `recordClientEvent` 구현 교체 (= Phase C)

## 4. 아키텍처

### 4.1 신규 파일

```
app/src/main/resources/
└── logback-spring.xml                                  [신규]

app/src/main/java/com/pfplaybackend/api/common/
├── log/MdcHelper.java                                  [신규]
├── log/MdcTaskDecorator.java                           [신규]
└── log/MaskingPatterns.java                            [신규 — 마스킹 정규식 상수 카탈로그]

realtime/src/main/java/com/pfplaybackend/realtime/interceptor/
└── WebSocketMdcChannelInterceptor.java                 [신규]

테스트:
├── app/src/test/java/.../log/MdcHelperTest.java                       [신규]
├── app/src/test/java/.../log/MdcTaskDecoratorTest.java                [신규]
├── app/src/test/java/.../log/MaskingPatternsTest.java                 [신규]
├── app/src/test/java/.../log/LogbackJsonConfigTest.java               [신규]
└── realtime/.../interceptor/WebSocketMdcChannelInterceptorTest.java   [신규]
```

### 4.2 수정 파일

```
app/build.gradle                                         (+ 1 line: logstash-logback-encoder 의존성)
app/src/main/resources/application.yml                   (필요시 logback 관련 키 — 가능한 한 logback-spring.xml 안에 셀프 컨테인)
app/src/main/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptor.java
  → ThreadLocal CURRENT 제거 / MDC put-clear 격상 / current() static = MDC.get 위임
app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java
  → userActivityLogExecutor 에 setTaskDecorator(new MdcTaskDecorator())
app/src/main/java/com/pfplaybackend/api/common/config/web/WebMvcConfig.java
  (이미 RequestIdInterceptor 등록 중 — 무변경 가능성 높음, 확인만)
realtime/src/main/java/com/pfplaybackend/realtime/config/WebSocketConfig.java
  → configureClientInboundChannel(...) override 추가, WebSocketMdcChannelInterceptor wired

(Domain event listener 측은 try-with-resources 도입이 광범위 — 단계적 진입:
 핵심 listener 1~3개만 본 spec 범위, 나머지는 별건. 본 spec 은 helper 와
 사용 예시 listener 1 — UserActivityLogListener 의 partyroomId 출현 케이스 —
 만 적용. 다른 listener 들은 후속 polish PR 로 점진 적용)
```

### 4.3 의존성

```gradle
// app/build.gradle 신규 1 line
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

- Spring Boot 3.2.x BOM 이 SLF4J/logback-classic 을 이미 관리. logstash-encoder 만 명시.
- 버전 7.4 는 Spring Boot 3.2.x 호환 검증된 stable (logback 1.4.x 라인 호환)

## 5. logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <!-- local: 콘솔 가독성 패턴. dev 도구 / 개발자 콘솔용 -->
  <springProfile name="local">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [requestId=%X{requestId:-}, userId=%X{userId:-}, partyroomId=%X{partyroomId:-}] %logger{36} - %msg%n</pattern>
        <charset>UTF-8</charset>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="STDOUT"/>
    </root>
  </springProfile>

  <!-- dev/staging/prod: JSON encoder + masking, Cloud Logging jsonPayload 정합 -->
  <springProfile name="dev,staging,prod">
    <appender name="JSON_STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <!-- 기본 timestamp/level/logger/message/stackTrace 외에 MDC 자동 emit -->
        <includeMdcKeyName>requestId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
        <includeMdcKeyName>sessionId</includeMdcKeyName>
        <includeMdcKeyName>partyroomId</includeMdcKeyName>

        <!-- Cloud Logging severity 매핑 (Spring level → GCP severity) -->
        <fieldNames>
          <timestamp>timestamp</timestamp>
          <level>severity</level>
          <logger>logger</logger>
          <thread>thread</thread>
          <message>message</message>
          <stackTrace>exception</stackTrace>
        </fieldNames>

        <!-- 마스킹: 정규식 기반 (MaskingPatterns 카탈로그) -->
        <jsonGeneratorDecorator class="com.pfplaybackend.api.common.log.MaskingJsonGeneratorDecorator"/>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON_STDOUT"/>
    </root>
  </springProfile>

</configuration>
```

> 구현 노트: `logstash-logback-encoder` 의 `LogstashEncoder` 가 GCP severity 키 (`severity`) 와 `timestamp` 를 ISO8601 으로 emit 하도록 `fieldNames` 매핑. Cloud Logging 이 `severity` 를 자동 인식해 콘솔에서 색상 표시 (INFO/WARN/ERROR).

## 6. Masking

### 6.1 패턴 카탈로그 (`MaskingPatterns.java`)

```java
public final class MaskingPatterns {
    private MaskingPatterns() {}

    // Secret — 완전 마스킹
    public static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    public static final Pattern PASSWORD_KV = Pattern.compile("(?i)(password|password_hash|passwordhash|pwd)[\"']?\\s*[:=]\\s*[\"']?[^\\s,\"'}]+");
    public static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+");
    public static final Pattern XSRF_TOKEN = Pattern.compile("(?i)(X-XSRF-TOKEN|XSRF-TOKEN)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9-]+");
    public static final Pattern ADMIN_ACCESS_TOKEN_COOKIE = Pattern.compile("(?i)AdminAccessToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern SHARED_SESSION_TOKEN_COOKIE = Pattern.compile("(?i)SharedSessionToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern API_KEY_KV = Pattern.compile("(?i)(api[_-]?key|apikey|secret[_-]?key|client[_-]?secret)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");

    // PII — 일부 마스킹 (디버깅 가능한 형태로)
    // EMAIL: 첫 1자만 노출 (1-char local-part 도 안 leak 되게 — `{2}` 면 1-char local 이 non-match 로 통과되어 leak).
    public static final Pattern EMAIL = Pattern.compile("([\\w.+-])[\\w.+-]*@([\\w-]+(?:\\.[\\w-]+)+)");
    // IP_V4: lookbehind 와 lookahead 둘 다 `[\d.]` 로 막아 5+ octet decimal sequence 의 inner 4-window 차단.
    // (Chunk 1 implementer fix 2026-05-20: 원래 lookahead `(?!\d)` 는 `192.168.1.1.2.3` 의 inner `192.168.1.1`
    //  이 `.2` 로 끝나는 형태에서 통과 — `[\d.]` 로 확장 필요.)
    public static final Pattern IP_V4 = Pattern.compile("(?<![\\d.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}(?![\\d.])");
}
```

### 6.2 마스킹 적용 메커니즘

`MaskingJsonGeneratorDecorator` (구현 신규):
- logstash-encoder 7.4 의 SPI: `JsonGeneratorDecorator { JsonGenerator decorate(JsonGenerator gen) }` 구현
- 반환할 `JsonGenerator` 는 위임형 wrapper — `writeString(String)` / `writeRawValue(String)` 만 가로채서 패턴 매치 → 치환, 나머지 메서드는 delegate 호출
- **필드 한정 적용**: `writeFieldName(String name)` 을 추적해 현재 필드가 `message` 또는 `exception` 일 때만 마스킹. logger / MDC / timestamp / severity / thread 는 통과
- 치환 후 결과를 wrapped generator 의 `writeString(masked)` 으로 emit

```java
public class MaskingJsonGeneratorDecorator implements JsonGeneratorDecorator {
    private static final Set<String> MASKABLE_FIELDS = Set.of("message", "exception");

    @Override
    public JsonGenerator decorate(JsonGenerator gen) {
        return new MaskingJsonGenerator(gen);
    }

    static class MaskingJsonGenerator extends JsonGeneratorDelegate {
        private String currentField;

        MaskingJsonGenerator(JsonGenerator d) { super(d); }

        @Override
        public void writeFieldName(String name) throws IOException {
            this.currentField = name;
            super.writeFieldName(name);
        }

        @Override
        public void writeString(String text) throws IOException {
            super.writeString(MASKABLE_FIELDS.contains(currentField) ? mask(text) : text);
        }

        @Override
        public void writeString(char[] text, int offset, int len) throws IOException {
            if (MASKABLE_FIELDS.contains(currentField)) {
                String masked = mask(new String(text, offset, len));
                super.writeString(masked);
            } else {
                super.writeString(text, offset, len);
            }
        }

        @Override
        public void writeRawValue(String text) throws IOException {
            super.writeRawValue(MASKABLE_FIELDS.contains(currentField) ? mask(text) : text);
        }

        private String mask(String input) {
            if (input == null || input.isEmpty()) return input;
            String out = input;
            // secret 먼저 (PII 보다 우선) — JWT/Bearer/cookies/api_key/password_kv
            out = MaskingPatterns.JWT.matcher(out).replaceAll("<jwt-redacted>");
            out = MaskingPatterns.BEARER_TOKEN.matcher(out).replaceAll("Bearer <redacted>");
            out = MaskingPatterns.PASSWORD_KV.matcher(out).replaceAll("$1=<redacted>");
            out = MaskingPatterns.ADMIN_ACCESS_TOKEN_COOKIE.matcher(out).replaceAll("AdminAccessToken=<redacted>");
            out = MaskingPatterns.SHARED_SESSION_TOKEN_COOKIE.matcher(out).replaceAll("SharedSessionToken=<redacted>");
            out = MaskingPatterns.XSRF_TOKEN.matcher(out).replaceAll("$1=<redacted>");
            out = MaskingPatterns.API_KEY_KV.matcher(out).replaceAll("$1=<redacted>");
            // PII — secret 패턴이 매치되지 않은 잔여 영역에 적용
            out = MaskingPatterns.EMAIL.matcher(out).replaceAll("$1***@$2");
            out = MaskingPatterns.IP_V4.matcher(out).replaceAll("$1.xxx");
            return out;
        }
    }
}
```

매치 실패 = 그대로 통과 (무손실)

### 6.3 제약

- **Logger name / MDC value 는 마스킹 적용 안 함** (이미 비-secret 필드만 들어가는 게 정책)
- **Message 본문 + exception stackTrace** 에 적용
- **새 secret 패턴 발견 시** `MaskingPatterns` 에 추가 + 단위 테스트 추가 (정규식 진화 위치 단일)
- **IP_V4 의 accepted limitation**: pure regex 로 `from 1.2.3.4`(IP) 와 `version 1.2.3.4`(semver) 의 의미 구분 불가능 — 후자도 의도적으로 redact 됨. semver/build 번호는 비-PII 라 무해. 만약 운영 단계에서 디버깅 시 semver redaction 이 거슬리면 `MaskingPatterns.IP_V4` 를 `(?<=from |ip[:=] |addr[:=] |host[:=] )...` 같은 prefix-anchored 정규식으로 진화 가능
- **MaskingJsonGenerator state 한계**: `currentField` 가 `writeFieldName` 으로만 갱신되어 *array 원소* 안에선 직전 필드 이름이 잔존. logstash-encoder 가 flat field 구조라 현재 무영향 — array 형태 필드 도입 시 재검토. 본 spec 범위에선 flat key→string 만 다룸
- **string emit 메서드 coverage**: `writeString(String)` + `writeString(char[], int, int)` + `writeRawValue(String)` 3종 override. `writeRaw(String)` 은 logstash-encoder 가 거의 사용 안 함 — 운영 중 stack trace 누출 발견 시 추가 override

## 7. MDC

### 7.1 표준 4 field

| field | 출처 | put 시점 | clear 시점 |
|---|---|---|---|
| `requestId` | `X-Request-Id` header 또는 자동 생성 8자 | RequestIdInterceptor.preHandle | RequestIdInterceptor.afterCompletion |
| `userId` | SecurityContext / JWT principal | preHandle (인증된 경우) + WebSocketMdcChannelInterceptor.preSend | afterCompletion / afterSendCompletion |
| `sessionId` | STOMP session id | WebSocketMdcChannelInterceptor.preSend | afterSendCompletion |
| `partyroomId` | 도메인 이벤트 payload | MdcHelper.scope(...) try-with-resources | scope.close() |

null 값은 MDC.put 자체 호출 안 함 (encoder 가 자동으로 omit).

### 7.2 RequestIdInterceptor 격상 코드 (요지)

```java
@Component
public class RequestIdInterceptor implements HandlerInterceptor {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "requestId";

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER_ID = "userId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = extractOrGenerate(request);
        MDC.put(MDC_REQUEST_ID, requestId);

        String userId = extractUserId(request);  // SecurityContextHolder 에서 principal.uid
        if (userId != null) MDC.put(MDC_USER_ID, userId);

        request.setAttribute(REQUEST_ID_ATTR, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_USER_ID);
    }

    /** Backward compat: A1 호출지들이 사용. ThreadLocal 제거 후 MDC 위임. */
    public static String current() {
        return MDC.get(MDC_REQUEST_ID);
    }

    private String extractOrGenerate(HttpServletRequest request) { /* 기존 sanitize + UUID 8자 */ }
    private String extractUserId(HttpServletRequest request) { /* SecurityContext 확인 */ }
}
```

### 7.3 WebSocketMdcChannelInterceptor

```java
@Component
public class WebSocketMdcChannelInterceptor implements ChannelInterceptor {
    private static final String MDC_SESSION_ID = "sessionId";
    private static final String MDC_USER_ID = "userId";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null) {
            String sessionId = accessor.getSessionId();
            if (sessionId != null) MDC.put(MDC_SESSION_ID, sessionId);

            Principal user = accessor.getUser();
            if (user != null) MDC.put(MDC_USER_ID, user.getName());
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_USER_ID);
    }
}
```

WebSocketConfig wiring:
```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(webSocketMdcChannelInterceptor);
}
```

> **MDC propagation 전제**: Spring 의 inbound channel default 는 `ExecutorSubscribableChannel` 단일 디스패치 — preSend 와 @MessageMapping handler 가 같은 thread 라 MDC 가 자동 가시. **만약 향후 `registration.taskExecutor(...)` 추가로 thread pool 분리되면** TaskDecorator wiring 도 그 executor 에 추가해야 함 (이번 spec 범위 밖, 변경 발생 시 함께 처리).

### 7.4 MdcTaskDecorator

```java
/**
 * Producer thread 의 MDC context 를 worker thread 로 복사.
 *
 * <p>Best-effort restore: finally 의 `prev` 는 worker 의 *기존* MDC (보통 clean pool thread 라 null).
 * pool thread 에 stale MDC 가 leftover 라면 그 값을 복원 — 그건 별도 코드 path 의 leak 이고,
 * 본 decorator 는 *그 leak 을 보존* 한다 (decorator 가 leak fix 책임 아님).
 * 정상 case 에서는 `prev == null` 이라 MDC.clear() = 깨끗한 thread 복원.
 */
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            try {
                if (context != null) MDC.setContextMap(context);
                else MDC.clear();
                runnable.run();
            } finally {
                if (prev != null) MDC.setContextMap(prev);
                else MDC.clear();
            }
        };
    }
}
```

AsyncConfig 적용:
```java
@Bean(name = UAL_EXECUTOR_BEAN)
public ThreadPoolTaskExecutor userActivityLogExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    // ... 기존 설정 ...
    exec.setTaskDecorator(new MdcTaskDecorator());
    exec.initialize();
    return exec;
}
```

### 7.5 MdcHelper.scope + MdcScope sub-interface (checked exception 회피)

`AutoCloseable.close()` 는 checked `throws Exception` 이라 caller 가 매번 try-catch 또는 throws 선언 필요. 적용 listener 가 늘어날수록 catch 보일러플레이트 누적. **`MdcScope` 라는 sub-interface** 로 `close()` 시그니처에서 `throws` 제거 (unchecked):

```java
public interface MdcScope extends AutoCloseable {
    /** AutoCloseable 의 throws Exception 을 unchecked 로 override — 호출자 catch 없이 try-with-resources 사용 가능. */
    @Override
    void close();
}

public final class MdcHelper {
    private MdcHelper() {}

    private static final MdcScope NOOP = () -> {};

    /** try-with-resources 로 MDC 키 스코프 진입/복원. value null 이면 no-op MdcScope. */
    public static MdcScope scope(String key, Object value) {
        if (value == null) return NOOP;

        String prev = MDC.get(key);
        MDC.put(key, String.valueOf(value));
        return () -> {
            if (prev != null) MDC.put(key, prev);
            else MDC.remove(key);
        };
    }
}
```

사용 (UserActivityLogListener 의 partyroomId 출현 케이스 — 실제 메서드: `on(CrewAccessedEvent e)`):
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async(AsyncConfig.UAL_EXECUTOR_BEAN)
public void on(CrewAccessedEvent e) {
    try (var ignored = MdcHelper.scope("partyroomId", e.getPartyroomId().getId())) {
        UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
                ? UserActivityEventType.PARTYROOM_ENTERED
                : UserActivityEventType.PARTYROOM_EXITED;
        log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
            JsonMetadata.empty(), e.getOccurredAt());
        // log() 내부 / 또는 이후 log.info 호출 시 partyroomId MDC 가 emit 됨
    }
    // close() unchecked, 별도 catch 불필요
}
```

> **본 spec 의 적용 범위**: helper 신설 + 호출 예시 1개 (`UserActivityLogListener.on(CrewAccessedEvent)` 만). 다른 listener 들 (`PartyroomCreatedEvent`, `CrewPenalizedEvent`, `AdminCrewPenalizedEvent`, `MemberTierChangedEvent`, `UserAccountWithdrawnEvent`, `MemberProfileInitializedEvent` 등) 의 try-with-resources 적용은 후속 polish PR — 점진 적용해도 미적용 listener 는 단순히 partyroomId MDC 가 없을 뿐 (기존 동작 동일).

## 8. Data Flow

```
HTTP Request
  └── RequestIdInterceptor.preHandle → MDC.put(requestId, userId)
       └── Controller → Service → log.info(...)
            └── LogstashEncoder → jsonPayload: { message, requestId, userId, ... } → stdout
                 └── Cloud Run/GCE capture → Cloud Logging (jsonPayload indexed)
       └── afterCompletion → MDC.remove

WebSocket Frame (CONNECT, SUBSCRIBE, SEND)
  └── WebSocketMdcChannelInterceptor.preSend → MDC.put(sessionId, userId)
       └── @MessageMapping handler → log.info(...) — sessionId/userId emit
       └── afterSendCompletion → MDC.remove

@Async @TransactionalEventListener (AFTER_COMMIT)
  └── userActivityLogExecutor.execute(...) — TaskDecorator wraps runnable
       └── Decorator: MDC.setContextMap(producer MDC copy)
            └── Listener 진입부: try (var = MdcHelper.scope("partyroomId", ...)) {
                 └── log.info(...) — producer's requestId/userId/sessionId + partyroomId emit
            }  // partyroomId 복원/제거
       └── Decorator finally: MDC.clear()
```

## 9. Error handling

- **MDC null safety**: 모든 put 전 null check. null = 키 미설정 (omit)
- **Interceptor 예외**: Spring 보장 — preHandle return true 후 발생한 예외도 afterCompletion 호출
- **TaskDecorator 예외**: runnable.run() throw 해도 finally 가 MDC.clear 보장
- **MdcHelper.scope finally**: try-with-resources 가 close() 보장. close() 안 throw
- **Masking 누락 risk**: 신규 secret 패턴 발견 시 `MaskingPatterns` 카탈로그 추가 (config-driven 진화). 운영 단계서 prod hotfix 가능

## 10. Testing

### 10.1 단위 테스트

| 테스트 | 의도 |
|---|---|
| `MaskingPatternsTest` | 각 정규식 (JWT, EMAIL, IP_V4, PASSWORD_KV, BEARER, XSRF, ADMIN/SHARED_SESSION, API_KEY) 입력 → 매치 결과 검증. positive + negative 케이스 |
| `MdcHelperTest` | scope try-with-resources / 이전 값 복원 / null 값 no-op / nested scope |
| `MdcTaskDecoratorTest` | 외부 thread MDC → 내부 thread 복사 + clear / 외부 MDC 없을 때 (null context) / 예외 시 cleanup |
| `RequestIdInterceptorTest` (기존 확장) | preHandle MDC put / afterCompletion clear / userId nullable / current() = MDC.get / sanitize 보존 |
| `WebSocketMdcChannelInterceptorTest` | StompHeaderAccessor mock → sessionId/userId MDC / preSend + afterSendCompletion lifecycle / null user/sessionId 안전 |
| `LogbackJsonConfigTest` | logback-spring.xml 로딩 검증. profile=local → ConsoleAppender + PatternLayout. profile=dev/staging/prod → JSON_STDOUT + LogstashEncoder. 4 MDC field includeMdcKeyName 확인 |

### 10.2 통합 테스트

| 테스트 | 의도 |
|---|---|
| `UserActivityLogListenerMdcIT` (신규 또는 기존 IT 확장) | @Async + AFTER_COMMIT 시 partyroomId/userId 가 listener thread 의 log 에 emit 됨. ListAppender 로 capture |
| 기존 920 tests | 회귀 zero 검증 (`./gradlew :app:test`) |

### 10.3 회귀 잠금

- A1 의 `RequestIdInterceptor.current()` 호출자들 (`DjCommandService` 6곳 등) 은 무수정 PASS (MDC.get 위임)
- 기존 모든 log.info() 호출지 무변경 (logger API 동일)
- ArchUnit 영향 무 (common 모듈 내부 구조)

### 10.4 Async dispatch 미해소 (deferred)

기존 `RequestIdInterceptor` Javadoc 은 "Phase B MDC 전환 시 해소" 라고 명시하나, 그 주석이 가리킨 *async dispatch* (Spring MVC `DeferredResult`/`Callable`) 경로는 본 spec 범위 밖. MVC async interceptor (`MdcCallableProcessingInterceptor`) 추가 wiring 이 필요하나 pfplay-platform 에서 그 패턴 사용 빈도가 미미 — Phase A6 ThreadLocal 단계의 best-effort 한계를 그대로 상속. 본 spec 의 MDC 격상은 **동기 servlet 요청 + STOMP frame + @Async listener** 까지 cover, MVC async dispatch 는 별도 follow-up.

### 10.5 추가 테스트 케이스 (reviewer 권고)

- `MaskingPatternsTest`:
  - empty string 입력 → no-op (NPE 안 남)
  - multi-line stackTrace 안에 email 2개 + IP 2개 → 모두 마스킹
  - 1-char local-part email (`a@example.com`) → `a***@example.com` (leak 없음 — `{1}` 정합)
  - IP boundary: `cluster 192.168.1.1.2.3` 처럼 5+ octet decimal sequence 에서 inner overlap 미매칭 (lookbehind/lookahead 정합). semver-like `1.2.3.4` 단일 시퀀스는 *의도적으로 redact* — §6.3 limitation 참조
  - cookie 형식: `Cookie: AdminAccessToken=abc.def; SharedSessionToken=xyz` → 둘 다 `<redacted>`
- `MdcHelperTest`:
  - nested scope: outer `partyroomId=X` → inner `partyroomId=Y` → inner close → MDC.get("partyroomId")=="X"
- `MdcTaskDecoratorTest`:
  - producer MDC empty + worker thread 에 leftover MDC → 정상 case 에선 leftover 복원 (best-effort 정합), 신규 task 시작 시 깨끗한 producer context 적용 확인

## 11. 보안

- **Secret 마스킹은 best-effort** (정규식 기반, 100% 보장 아님). 알려진 패턴 카탈로그화 + 운영 중 진화
- **MDC field 자체에는 secret 미적재** (정책) — userId/partyroomId 는 식별자 (PK), 비-secret
- **stack trace 안의 PII** — exception 메시지 안에 email/IP 가 들어갈 수 있음. masking 패턴이 stackTrace 필드에도 적용됨

## 12. 비용 / 운영

- **추가 ingest 양**: JSON wrapping bytes 로 line 당 ~2배 증가 가정. Phase A plan §비용 추정의 worst case 가 50% 가정 → ~100% (full JSON) 로 보정 시 ~1.3 GB/월 → ~2.6 GB/월. 무료 50 GiB/월 대비 ~5%
- **CPU 오버헤드**: LogstashEncoder + Masking 정규식 = 미미 (분당 수 K line 수준). 측정 가치 있는 hot spot 아님
- **MDC 메모리**: ThreadLocal Map, 키 4개. 무시 가능

## 13. 마이그레이션 / 배포

- DB 변경 zero
- env 변경 zero
- 배포 순서: develop → stg 자동배포 → log 출력 format 변경 visual 검증 (콘솔에 JSON 출력) → 안정화 → release/main
- Rollback (PR1): logback-spring.xml 삭제 → Spring Boot 기본 콘솔 pattern 자동 복귀. 의존성 제거는 별도 PR. Zero-risk
- Rollback (PR2): commit revert (RequestIdInterceptor MDC 격상, WebSocketMdcChannelInterceptor 신설, AsyncConfig TaskDecorator 설정). MDC.put 자체는 encoder 없이도 무해 (no-op visible output), 단 위 변경들이 의존하는 helper/decorator 가 동시에 revert 되어야 함

## 14. 작업 분량 추정

- B1 (logback-spring.xml + masking + 의존성 + MaskingJsonGeneratorDecorator SPI 탐색): ~1.5일 + 테스트 ~1일
- B2 (RequestIdInterceptor 격상 + WebSocket interceptor + TaskDecorator + MdcScope/Helper + listener 예시): ~1일 + 통합테스트 (ListAppender + Awaitility) ~1일
- 통합 검증 / PR 분할 / 문서: ~0.5일

총 **약 4~5일** (single dev) — reviewer 권고대로 보정. 결정 게이트 잠금 + 자율 진행.

## 15. PR 분할 ([[feedback_pr_series_workflow]])

- **PR1 (B1)**: logback-spring.xml + 의존성 + MaskingPatterns + MaskingJsonGeneratorDecorator + LogbackJsonConfigTest + MaskingPatternsTest. 머지 시 시각적 확인 가능 (콘솔 JSON 출력 시작)
- **PR2 (B2)**: MdcHelper + MdcTaskDecorator + RequestIdInterceptor 격상 + WebSocketMdcChannelInterceptor + WebSocketConfig wiring + AsyncConfig 적용 + 단위/통합 테스트. 머지 시 jsonPayload 안에 MDC 4 field 출현 시작

> 머지 순서: PR1 먼저 (JSON 출력만이라도 enable), PR2 가 그 위에 MDC 추가. PR1 단독으로도 가치 있음 (MDC 없는 jsonPayload 라도 message/severity/logger indexed).

## 16. 후속/별건

- **Phase A1 호출지의 try-with-resources 점진 적용**: UserActivityLogListener 외 listener (ChatNotificationListener, PartyroomNotificationListener 등) 들에 `MdcHelper.scope("partyroomId", ...)` 적용. PR2 머지 후 별도 polish PR
- **Phase B3 (Actuator/Micrometer)**: 1주 실측 후 진행
- **Phase B4 (sampling/retention ADR)**: 1주 실측 데이터 위에 작성
- **Phase C (Cloud Run logging sink)**: B3/B4 결정 후

## 17. 관련 메모리

- [[feedback_observability_stack]] — Phase B1+B2 선제 결정 (2026-05-20), 가격 fact-check
- [[reference_pfplay_platform_jdk]] — Gradle 호출 JDK env
- [[feedback_pr_series_workflow]] — chunk + atomic group (PR1 B1 + PR2 B2)
- [[feedback_autonomous_execution]] — 3 결정 게이트 후 자율 진행
- [[feedback_korean_issue_commit_pr]] — 한글 PR/commit
- [[feedback_elegant_no_code_dirtying]] — RequestIdInterceptor 의 ThreadLocal 제거 (Phase A6 가 이미 예고)
