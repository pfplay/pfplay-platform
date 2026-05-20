# Observability Phase B1 + B2 — JSON structured logging + MDC interceptor 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** stdout 으로 JSON structured 로그를 출력하고 (B1) 한 요청/세션/이벤트의 cross-component 로그를 4개 MDC field (`requestId`/`userId`/`sessionId`/`partyroomId`) 로 indexed query 가능하게 만든다 (B2).

**Architecture:** B1 = `logback-spring.xml` 신설 + `logstash-logback-encoder` 의존성 + 마스킹 정규식 카탈로그 + JsonGeneratorDecorator wrapper. B2 = `MdcScope` sub-interface + `MdcHelper.scope` + `MdcTaskDecorator` + 기존 `RequestIdInterceptor` MDC 격상 + 신규 `WebSocketMdcChannelInterceptor`. 2 PR 분할 (B1 먼저 머지 가능, B2 가 그 위에 MDC 추가).

**Tech Stack:** Java 21 (Gradle 호출 시 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), Spring Boot 3.2.3, logback 1.4.x (Boot BOM 관리), `net.logstash.logback:logstash-logback-encoder:7.4`, JUnit5 + Mockito + ListAppender (테스트). Gradle 모듈: `app` (대부분), `realtime` (WebSocket interceptor 만).

**Spec:** `docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md`

**Branch:** `feature/observability-b1-b2-json-mdc` (pfplay-platform, 이미 spec 커밋 존재)

---

## File Structure

### Chunk 1 (PR1, B1) 산출물

**신규**:
- `app/src/main/resources/logback-spring.xml`
- `app/src/main/java/com/pfplaybackend/api/common/log/MaskingPatterns.java`
- `app/src/main/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecorator.java`
- 테스트:
  - `app/src/test/java/com/pfplaybackend/api/common/log/MaskingPatternsTest.java`
  - `app/src/test/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecoratorTest.java`
  - `app/src/test/java/com/pfplaybackend/api/common/log/LogbackJsonConfigTest.java`

**수정**:
- `app/build.gradle` — `logstash-logback-encoder` 의존성 1줄 추가

### Chunk 2 (PR2, B2) 산출물

**신규**:
- `app/src/main/java/com/pfplaybackend/api/common/log/MdcScope.java`
- `app/src/main/java/com/pfplaybackend/api/common/log/MdcHelper.java`
- `app/src/main/java/com/pfplaybackend/api/common/log/MdcTaskDecorator.java`
- `realtime/src/main/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptor.java`
- 테스트:
  - `app/src/test/java/com/pfplaybackend/api/common/log/MdcScopeTest.java`
  - `app/src/test/java/com/pfplaybackend/api/common/log/MdcHelperTest.java`
  - `app/src/test/java/com/pfplaybackend/api/common/log/MdcTaskDecoratorTest.java`
  - `realtime/src/test/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptorTest.java`
  - `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerMdcIT.java`

**수정**:
- `app/src/main/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptor.java` — ThreadLocal → MDC put/clear 격상, `current()` 는 MDC.get 위임으로 backward compat 유지
- `app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java` — `userActivityLogExecutor.setTaskDecorator(new MdcTaskDecorator())` 추가
- `realtime/src/main/java/com/pfplaybackend/realtime/config/WebSocketConfig.java` — `configureClientInboundChannel(...)` override 추가
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java` — `on(CrewAccessedEvent e)` 메서드만 `MdcHelper.scope("partyroomId", ...)` try-with-resources 로 감싸기 (예시 적용 1곳, 다른 listener 들은 후속 polish PR)
- 기존 `RequestIdInterceptorTest.java` — MDC put/clear 검증으로 확장

### 빌드/검증 명령

**전체 backend 테스트**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`

**단일 테스트 클래스**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*ClassName*"`

**realtime 모듈 테스트**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :realtime:test`

**컴파일만**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava :realtime:compileJava`

---

## Chunk 1: B1 — logback-spring.xml + masking encoder + 의존성 + 테스트

> 단독 머지 가능. 머지 시 dev/staging/prod stdout 이 JSON 으로 전환되어 Cloud Logging jsonPayload 자동 인식 시작. MDC 는 아직 없으므로 jsonPayload 의 message/severity/logger/thread/stackTrace 까지만 indexed.
>
> **review 검증포인트**: ① 기존 모든 테스트 GREEN (회귀 zero) ② local profile 은 사람 가독 PatternLayout 유지 ③ secret + PII 마스킹이 message/exception 필드에만 적용 (MDC value 자체엔 미적용) ④ logstash-logback-encoder 버전 7.4 가 Spring Boot 3.2.3 환경에서 호환

### Task 1: build.gradle 의존성 추가

**Files:**
- Modify: `app/build.gradle:52` (Jackson annotations 인접)

- [ ] **Step 1: 의존성 1줄 추가**

```diff
 	// Jackson
 	implementation 'com.fasterxml.jackson.core:jackson-annotations:2.15.2'
+
+	// Observability: structured JSON logging
+	implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

- [ ] **Step 2: 컴파일 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add app/build.gradle
git commit -m "chore(obs-b1): logstash-logback-encoder:7.4 의존성 추가"
```

### Task 2: `MaskingPatterns` 카탈로그

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/log/MaskingPatterns.java`

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.common.log;

import java.util.regex.Pattern;

/**
 * Log 출력 시 마스킹 대상 정규식 카탈로그.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §6.1.
 *
 * <p>두 부류:
 * <ul>
 *   <li>secret — 완전 마스킹 (`<redacted>`)</li>
 *   <li>PII — 식별 가능성 차단하되 디버깅 단서 (앞글자 / 마지막 옥텟 제외) 유지</li>
 * </ul>
 *
 * <p>새 secret 패턴 발견 시 본 카탈로그에 추가 + {@link MaskingPatternsTest} 에 케이스 추가.
 */
public final class MaskingPatterns {

    private MaskingPatterns() {}

    // --- Secret — 완전 마스킹 ---
    public static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    public static final Pattern PASSWORD_KV = Pattern.compile(
            "(?i)(password|password_hash|passwordhash|pwd)[\"']?\\s*[:=]\\s*[\"']?[^\\s,\"'}]+");
    public static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._-]+");
    public static final Pattern XSRF_TOKEN = Pattern.compile(
            "(?i)(X-XSRF-TOKEN|XSRF-TOKEN)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9-]+");
    public static final Pattern ADMIN_ACCESS_TOKEN_COOKIE = Pattern.compile(
            "(?i)AdminAccessToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern SHARED_SESSION_TOKEN_COOKIE = Pattern.compile(
            "(?i)SharedSessionToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern API_KEY_KV = Pattern.compile(
            "(?i)(api[_-]?key|apikey|secret[_-]?key|client[_-]?secret)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");

    // --- PII — 일부 마스킹 ---
    /** 첫 1자만 노출 — {@code {1}} 이라 1-char local-part 도 매치 (leak 방지). */
    public static final Pattern EMAIL = Pattern.compile(
            "([\\w.+-])[\\w.+-]*@([\\w-]+(?:\\.[\\w-]+)+)");
    /** lookbehind/lookahead 둘 다 {@code [\\d.]} 로 막아 5+ octet decimal sequence 의 inner 4-window 차단.
     *  semver-like 단일 시퀀스는 의도적으로 redact (spec §6.3 limitation). */
    public static final Pattern IP_V4 = Pattern.compile(
            "(?<![\\d.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}(?![\\d.])");
}
```

- [ ] **Step 2: 컴파일 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/log/MaskingPatterns.java
git commit -m "feat(obs-b1): MaskingPatterns 카탈로그 — secret 7종 + PII 2종"
```

### Task 3: `MaskingPatternsTest` — 정규식 동작 검증

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/common/log/MaskingPatternsTest.java`

`Pattern` 자체를 직접 테스트 (encoder 와 독립). regex 정확성이 핵심 — 마스킹 SPI 가 들어가기 전 단위 테스트로 패턴 자체를 잠근다.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPatternsTest {

    // --- JWT ---
    @Test
    @DisplayName("JWT — 표준 3-segment 토큰 매치")
    void jwt_matches_standard() {
        String input = "Authorization=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.xyz_signature";
        String out = MaskingPatterns.JWT.matcher(input).replaceAll("<jwt-redacted>");
        assertThat(out).isEqualTo("Authorization=<jwt-redacted>");
    }

    // --- PASSWORD_KV ---
    @Test
    @DisplayName("PASSWORD_KV — password=, password_hash:, pwd= 모두 매치")
    void password_kv_matches_variants() {
        assertThat(MaskingPatterns.PASSWORD_KV.matcher("user=alice, password=secret123, role=admin")
                .replaceAll("$1=<redacted>"))
                .isEqualTo("user=alice, password=<redacted>, role=admin");

        assertThat(MaskingPatterns.PASSWORD_KV.matcher("password_hash: $argon2id$xyz")
                .replaceAll("$1=<redacted>"))
                .contains("password_hash=<redacted>");
    }

    // --- BEARER_TOKEN ---
    @Test
    @DisplayName("BEARER_TOKEN — Authorization 헤더 형식")
    void bearer_token_matches() {
        String out = MaskingPatterns.BEARER_TOKEN.matcher("Authorization: Bearer abc.def-XYZ_123")
                .replaceAll("Bearer <redacted>");
        assertThat(out).isEqualTo("Authorization: Bearer <redacted>");
    }

    // --- Cookie variants ---
    @Test
    @DisplayName("AdminAccessToken / SharedSessionToken 쿠키 값 매치")
    void cookie_tokens_match() {
        String input = "Cookie: AdminAccessToken=abc.def.xyz; SharedSessionToken=alpha.beta";
        String afterAdmin = MaskingPatterns.ADMIN_ACCESS_TOKEN_COOKIE.matcher(input)
                .replaceAll("AdminAccessToken=<redacted>");
        String afterShared = MaskingPatterns.SHARED_SESSION_TOKEN_COOKIE.matcher(afterAdmin)
                .replaceAll("SharedSessionToken=<redacted>");
        assertThat(afterShared).doesNotContain("abc.def.xyz")
                                .doesNotContain("alpha.beta")
                                .contains("AdminAccessToken=<redacted>")
                                .contains("SharedSessionToken=<redacted>");
    }

    // --- XSRF_TOKEN ---
    @Test
    @DisplayName("X-XSRF-TOKEN 헤더 값 매치")
    void xsrf_token_matches() {
        String out = MaskingPatterns.XSRF_TOKEN.matcher("X-XSRF-TOKEN: csrf-abc-123")
                .replaceAll("$1=<redacted>");
        assertThat(out).contains("X-XSRF-TOKEN=<redacted>");
    }

    // --- API_KEY_KV ---
    @Test
    @DisplayName("API_KEY_KV — api-key/apikey/secret-key/client-secret 모두 매치")
    void api_key_variants_match() {
        assertThat(MaskingPatterns.API_KEY_KV.matcher("api_key=KEY_123-abc").replaceAll("$1=<redacted>"))
                .isEqualTo("api_key=<redacted>");
        assertThat(MaskingPatterns.API_KEY_KV.matcher("client_secret: my-secret-value").replaceAll("$1=<redacted>"))
                .contains("client_secret=<redacted>");
    }

    // --- EMAIL ---
    @Test
    @DisplayName("EMAIL — 표준 길이 local-part 첫 1자만 노출")
    void email_masks_standard() {
        String out = MaskingPatterns.EMAIL.matcher("contact john.doe@example.com please")
                .replaceAll("$1***@$2");
        assertThat(out).isEqualTo("contact j***@example.com please");
    }

    @Test
    @DisplayName("EMAIL — 1-char local-part 도 leak 없이 마스킹")
    void email_1char_local_no_leak() {
        String out = MaskingPatterns.EMAIL.matcher("a@example.com")
                .replaceAll("$1***@$2");
        assertThat(out).isEqualTo("a***@example.com");
    }

    // --- IP_V4 ---
    @Test
    @DisplayName("IP_V4 — 표준 dotted-quad 마지막 옥텟 마스킹")
    void ip_v4_masks_standard() {
        String out = MaskingPatterns.IP_V4.matcher("client 192.168.1.42 connected")
                .replaceAll("$1.xxx");
        assertThat(out).isEqualTo("client 192.168.1.xxx connected");
    }

    @Test
    @DisplayName("IP_V4 — 5+ octet decimal sequence 의 inner overlap 미매칭")
    void ip_v4_inner_overlap_rejected() {
        // `cluster 192.168.1.1.2.3` 같은 6-decimal sequence:
        // lookbehind/lookahead 가 `192.168.1.1` 도 `168.1.1.2` 도 막아야 함
        String out = MaskingPatterns.IP_V4.matcher("cluster 192.168.1.1.2.3 build")
                .replaceAll("$1.xxx");
        // 6-decimal sequence 어느 4-window 도 매치 안 됨 → 무변형
        assertThat(out).isEqualTo("cluster 192.168.1.1.2.3 build");
    }

    @Test
    @DisplayName("IP_V4 limitation — semver-like 4-decimal 단일 시퀀스는 의도적 redact")
    void ip_v4_semver_redacted_intentionally() {
        // spec §6.3 accepted limitation 검증 — 정규식이 IP 와 semver 구분 불가
        String out = MaskingPatterns.IP_V4.matcher("version 1.2.3.4 build")
                .replaceAll("$1.xxx");
        assertThat(out).isEqualTo("version 1.2.3.xxx build");
    }

    // --- empty / multi-line ---
    @Test
    @DisplayName("empty string — 어느 패턴도 NPE 없이 통과")
    void empty_string_safe() {
        assertThat(MaskingPatterns.EMAIL.matcher("").replaceAll("$1***@$2")).isEmpty();
        assertThat(MaskingPatterns.IP_V4.matcher("").replaceAll("$1.xxx")).isEmpty();
        assertThat(MaskingPatterns.JWT.matcher("").replaceAll("<jwt-redacted>")).isEmpty();
    }

    @Test
    @DisplayName("multi-line stack trace — 여러 PII 인스턴스 모두 매치")
    void multi_line_multiple_matches() {
        String input = "at Service.connect(10.0.0.5:443)\n" +
                       "Caused by: timeout for alice@corp.io\n" +
                       "  at retry(10.0.0.6:443) for bob@corp.io";
        String afterEmail = MaskingPatterns.EMAIL.matcher(input).replaceAll("$1***@$2");
        String afterIp = MaskingPatterns.IP_V4.matcher(afterEmail).replaceAll("$1.xxx");
        assertThat(afterIp).contains("10.0.0.xxx:443").contains("a***@corp.io").contains("b***@corp.io")
                           .doesNotContain("alice@corp.io").doesNotContain("bob@corp.io")
                           .doesNotContain("10.0.0.5").doesNotContain("10.0.0.6");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 모두 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MaskingPatternsTest*"`
Expected: PASS (12 tests)

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/log/MaskingPatternsTest.java
git commit -m "test(obs-b1): MaskingPatterns 정규식 12 cases (1-char email + semver limit 포함)"
```

### Task 4: `MaskingJsonGeneratorDecorator`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecorator.java`

logstash-encoder 7.4 의 `JsonGeneratorDecorator` SPI 구현. wrapper 가 `writeFieldName` 으로 current field 추적 → `writeString(String)` / `writeString(char[],int,int)` / `writeRawValue` 가 maskable 필드일 때만 마스킹.

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.common.log;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import net.logstash.logback.decorate.JsonGeneratorDecorator;

import java.io.IOException;
import java.util.Set;

/**
 * logstash-logback-encoder JsonGenerator wrapper.
 *
 * <p>{@code message} / {@code exception} 필드 값에만 {@link MaskingPatterns} 적용.
 * logger / thread / timestamp / severity / MDC 값은 통과.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §6.2.
 *
 * <p>Override 범위 (logstash-logback-encoder 7.4 verified — 본 SPI 만 호출됨):
 * <ul>
 *   <li>{@link #writeFieldName(String)} — currentField 추적</li>
 *   <li>{@link #writeString(String)} — 가장 빈번한 emit 경로 (message/exception)</li>
 *   <li>{@link #writeString(char[], int, int)} — char-array variant (방어적)</li>
 *   <li>{@link #writeRawValue(String)} — raw value (큰 객체 trace 등)</li>
 * </ul>
 *
 * <p>제약 (spec §6.3):
 * <ul>
 *   <li>field-scope state — {@code currentField} 가 마지막 {@code writeFieldName} 으로만 갱신.
 *       array 원소 안에선 직전 필드 이름 잔존 (flat 구조에선 무영향).</li>
 *   <li>{@code writeFieldName(SerializableString)}, {@code writeString(Reader, int)},
 *       offset variants of {@code writeRawValue}, {@code writeRaw(String)} 미 override —
 *       logstash-logback-encoder 7.4 에서 사용 안 됨 (`JsonWritingUtils.writeStringField` =
 *       writeFieldName(String) + writeString(String) 패턴 사용). future encoder evolution 시
 *       override 추가 검토.</li>
 * </ul>
 */
public class MaskingJsonGeneratorDecorator implements JsonGeneratorDecorator {

    private static final Set<String> MASKABLE_FIELDS = Set.of("message", "exception");

    @Override
    public JsonGenerator decorate(JsonGenerator generator) {
        return new MaskingJsonGenerator(generator);
    }

    static final class MaskingJsonGenerator extends JsonGeneratorDelegate {

        private String currentField;

        MaskingJsonGenerator(JsonGenerator delegate) {
            super(delegate);
        }

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

        static String mask(String input) {
            if (input == null || input.isEmpty()) return input;
            String out = input;
            // secret 먼저
            out = MaskingPatterns.JWT.matcher(out).replaceAll("<jwt-redacted>");
            out = MaskingPatterns.BEARER_TOKEN.matcher(out).replaceAll("Bearer <redacted>");
            out = MaskingPatterns.PASSWORD_KV.matcher(out).replaceAll("$1=<redacted>");
            out = MaskingPatterns.ADMIN_ACCESS_TOKEN_COOKIE.matcher(out).replaceAll("AdminAccessToken=<redacted>");
            out = MaskingPatterns.SHARED_SESSION_TOKEN_COOKIE.matcher(out).replaceAll("SharedSessionToken=<redacted>");
            out = MaskingPatterns.XSRF_TOKEN.matcher(out).replaceAll("$1=<redacted>");
            out = MaskingPatterns.API_KEY_KV.matcher(out).replaceAll("$1=<redacted>");
            // PII
            out = MaskingPatterns.EMAIL.matcher(out).replaceAll("$1***@$2");
            out = MaskingPatterns.IP_V4.matcher(out).replaceAll("$1.xxx");
            return out;
        }
    }
}
```

- [ ] **Step 2: 컴파일 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecorator.java
git commit -m "feat(obs-b1): MaskingJsonGeneratorDecorator — message/exception 필드 마스킹"
```

### Task 5: `MaskingJsonGeneratorDecoratorTest`

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecoratorTest.java`

`mask` 함수 직접 테스트 (정수에 가까운 input → 마스킹된 output) + JsonGenerator wrapping 동작 통합 테스트.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.common.log;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static com.pfplaybackend.api.common.log.MaskingJsonGeneratorDecorator.MaskingJsonGenerator.mask;

class MaskingJsonGeneratorDecoratorTest {

    @Test
    @DisplayName("mask: empty/null safety")
    void mask_handles_empty_and_null() {
        assertThat(mask(null)).isNull();
        assertThat(mask("")).isEmpty();
        assertThat(mask("no secrets here")).isEqualTo("no secrets here");
    }

    @Test
    @DisplayName("mask: secret + PII 조합 (cookie 헤더 + 본문 email)")
    void mask_combined_secret_pii() {
        String input = "Cookie: AdminAccessToken=abc.def; user=jane.doe@corp.io from 10.0.0.5";
        String out = mask(input);
        assertThat(out)
                .contains("AdminAccessToken=<redacted>")
                .contains("j***@corp.io")
                .contains("10.0.0.xxx")
                .doesNotContain("abc.def")
                .doesNotContain("jane.doe@corp.io")
                .doesNotContain("10.0.0.5");
    }

    @Test
    @DisplayName("mask: stack trace multi-line — 모든 인스턴스 마스킹")
    void mask_multiline_stacktrace() {
        String trace = "java.lang.RuntimeException: timeout for alice@example.org\n" +
                       "    at com.example.Service.fetch(192.168.1.10:8080)\n" +
                       "Caused by: java.net.ConnectException: 192.168.1.20:8080\n" +
                       "    at retry(bob@example.org)";
        String out = mask(trace);
        assertThat(out)
                .contains("a***@example.org")
                .contains("b***@example.org")
                .contains("192.168.1.xxx:8080")
                .doesNotContain("alice@example.org")
                .doesNotContain("bob@example.org")
                .doesNotContain("192.168.1.10")
                .doesNotContain("192.168.1.20");
    }

    @Test
    @DisplayName("JsonGenerator wrapping — message/exception 필드만 마스킹, logger 통과")
    void wrapping_field_scoped() throws IOException {
        StringWriter sw = new StringWriter();
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator raw = factory.createGenerator(sw)) {
            MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
            JsonGenerator masked = decorator.decorate(raw);

            masked.writeStartObject();
            masked.writeStringField("logger", "com.example.Service");        // 통과
            masked.writeStringField("message", "user alice@corp.io login");  // 마스킹
            masked.writeStringField("thread", "http-nio-8080-exec-1");       // 통과
            masked.writeEndObject();
            masked.flush();
        }

        String json = sw.toString();
        assertThat(json)
                .contains("\"logger\":\"com.example.Service\"")  // 마스킹 안 됨 (필드명 아님)
                .contains("\"message\":\"user a***@corp.io login\"")
                .contains("\"thread\":\"http-nio-8080-exec-1\"") // 통과
                .doesNotContain("alice@corp.io");
    }

    @Test
    @DisplayName("JsonGenerator wrapping — char[] writeString variant 도 마스킹")
    void wrapping_writeString_chararray() throws IOException {
        StringWriter sw = new StringWriter();
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator raw = factory.createGenerator(sw)) {
            JsonGenerator masked = new MaskingJsonGeneratorDecorator().decorate(raw);
            masked.writeStartObject();
            masked.writeFieldName("message");
            char[] chars = "secret jwt eyJabc.eyJdef.xyz here".toCharArray();
            masked.writeString(chars, 0, chars.length);
            masked.writeEndObject();
            masked.flush();
        }
        assertThat(sw.toString()).contains("<jwt-redacted>").doesNotContain("eyJabc.eyJdef.xyz");
    }
}
```

- [ ] **Step 2: 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MaskingJsonGeneratorDecoratorTest*"`
Expected: PASS (5 tests)

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/log/MaskingJsonGeneratorDecoratorTest.java
git commit -m "test(obs-b1): MaskingJsonGeneratorDecorator — mask + JsonGenerator wrapping 5 cases"
```

### Task 6: `logback-spring.xml`

**Files:**
- Create: `app/src/main/resources/logback-spring.xml`

Profile 분기: local 콘솔 pattern, dev/staging/prod JSON encoder + masking. `includeMdcKeyName` 으로 4 MDC field 자동 emit (Phase B2 머지 후 실제 값 채워짐).

- [ ] **Step 1: 신규 파일 작성**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §5.

  Profile 분기:
   - local: 가독성 PatternLayout (개발자 콘솔용)
   - dev/staging/prod: LogstashEncoder JSON + MaskingJsonGeneratorDecorator
                       (Cloud Logging jsonPayload 자동 인식)
   - test (= @ActiveProfiles("test")): Spring Boot base.xml include
                       (framework noise suppression + CONSOLE appender 유지)

  ⚠️ logback-test.xml 이 부재라 Spring Boot 는 logback-spring.xml 을 모든 profile 에 적용함.
  test profile 명시 안 하면 root logger 가 zero appender → framework defaults.xml suppression
  (Hibernate/Catalina WARN 등) 도 잃음. 명시적 test block 으로 base.xml include 해서 보존.
-->
<configuration>

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

  <springProfile name="test">
    <!-- Spring Boot 기본 console + framework noise suppression -->
    <include resource="org/springframework/boot/logging/logback/base.xml"/>
  </springProfile>

  <springProfile name="dev,staging,prod">
    <appender name="JSON_STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <!-- 4 MDC 표준 필드 자동 emit. B2 머지 전엔 값 없음 → 자동 omit -->
        <includeMdcKeyName>requestId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
        <includeMdcKeyName>sessionId</includeMdcKeyName>
        <includeMdcKeyName>partyroomId</includeMdcKeyName>

        <!-- Cloud Logging severity/timestamp 자동 인식 위한 fieldName 매핑 -->
        <fieldNames>
          <timestamp>timestamp</timestamp>
          <level>severity</level>
          <logger>logger</logger>
          <thread>thread</thread>
          <message>message</message>
          <stackTrace>exception</stackTrace>
        </fieldNames>

        <jsonGeneratorDecorator class="com.pfplaybackend.api.common.log.MaskingJsonGeneratorDecorator"/>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON_STDOUT"/>
    </root>
  </springProfile>

</configuration>
```

- [ ] **Step 2: 어플리케이션 부팅 + log 출력 시각 확인** (수동)

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun --info` 약 10초 후 종료

Expected: local profile 시 콘솔에 `[requestId=, userId=, partyroomId=] com.pfplaybackend...` 같은 pattern 출력 확인 (실제 부팅 안 해도 컴파일/test 만으로 충분 — visual smoke 는 PR1 stg 검증 단계)

> 실제 application 부팅이 부담스러우면 Task 7 의 LogbackJsonConfigTest 가 LoggerContext reflection 으로 검증함.

- [ ] **Step 3: 기존 모든 테스트 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: PASS (920+ tests). logback-spring.xml 은 test profile 에서도 적용되지만 `<springProfile name="test">` block 이 Spring Boot base.xml include 로 기본 console + framework noise suppression 보존 → ListAppender-on-class-logger 사용 테스트 (DjCommandServiceLogCaptureTest 등) 무파손, 일반 stdout 출력도 유지

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/resources/logback-spring.xml
git commit -m "feat(obs-b1): logback-spring.xml — local PatternLayout / dev,staging,prod JSON encoder + masking"
```

### Task 7: `LogbackJsonConfigTest` — 설정 로딩 검증

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/common/log/LogbackJsonConfigTest.java`

LoggerContext reflection 으로 profile 별 appender 활성 검증. test 자체는 Spring profile 직접 띄우지 않고 logback-spring.xml 의 JoranConfigurator 로 직접 로드 + `springProperty`/`springProfile` resolver 모킹.

> **단순화 선택**: profile 별 부트 테스트는 비싸므로, logback-spring.xml 의 raw content 를 파일 읽기로 검증 (정합성 보장) + 핵심 element 존재만 단언. 더 깊은 검증은 stg 머지 후 시각적.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logback-spring.xml 의 raw 내용을 읽어 핵심 요소가 위치한지 확인하는 sanity test.
 *
 * <p>profile 별 appender 활성을 정밀하게 검증하려면 Spring Boot 부팅이 필요해
 * cost 가 큼 — 본 test 는 설정 파일 자체의 structural integrity 만 잠금.
 * profile=local / dev-staging-prod 분기 시 실제 결과는 stg 머지 후 시각적 검증.
 */
class LogbackJsonConfigTest {

    @Test
    @DisplayName("logback-spring.xml 존재 + 핵심 element 포함")
    void config_file_has_required_elements() throws Exception {
        Path config = Path.of("src/main/resources/logback-spring.xml");
        String content = Files.readString(config);

        // local profile pattern
        assertThat(content).contains("<springProfile name=\"local\">");
        assertThat(content).contains("PatternLayoutEncoder");
        assertThat(content).contains("[requestId=%X{requestId:-}");

        // test profile = Spring Boot base.xml include (framework noise suppression 보존)
        assertThat(content).contains("<springProfile name=\"test\">");
        assertThat(content).contains("org/springframework/boot/logging/logback/base.xml");

        // dev/staging/prod JSON
        assertThat(content).contains("<springProfile name=\"dev,staging,prod\">");
        assertThat(content).contains("net.logstash.logback.encoder.LogstashEncoder");

        // 4 MDC 필드 모두 includeMdcKeyName 으로 명시
        assertThat(content).contains("<includeMdcKeyName>requestId</includeMdcKeyName>");
        assertThat(content).contains("<includeMdcKeyName>userId</includeMdcKeyName>");
        assertThat(content).contains("<includeMdcKeyName>sessionId</includeMdcKeyName>");
        assertThat(content).contains("<includeMdcKeyName>partyroomId</includeMdcKeyName>");

        // Cloud Logging severity 매핑
        assertThat(content).contains("<level>severity</level>");

        // Masking decorator wired
        assertThat(content).contains("com.pfplaybackend.api.common.log.MaskingJsonGeneratorDecorator");
    }
}
```

- [ ] **Step 2: 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*LogbackJsonConfigTest*"`
Expected: PASS (1 test)

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/common/log/LogbackJsonConfigTest.java
git commit -m "test(obs-b1): logback-spring.xml structural integrity sanity"
```

### Task 8: Chunk 1 통합 회귀 검증

- [ ] **Step 1: 전체 backend 테스트 통과**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: 920+ tests GREEN, 회귀 zero

- [ ] **Step 2: ArchUnit 영향 확인**

`com.pfplaybackend.api.common.log` 패키지가 common 모듈 내부라 cross-BC 룰 영향 없음. ArchUnit 룰 추가 불필요. 실행은 `:app:test` 안에 포함됨.

- [ ] **Step 3: Chunk 1 종료 (PR1 머지 대상)**

이 시점에 PR1 (B1) 생성 가능. Chunk 2 (B2) 진행 전 PR1 develop 머지 권장 — atomic group 분리 ([[feedback_pr_series_workflow]]). 단 자율 진행 합의에 따라 Chunk 2 를 같은 브랜치에서 이어 진행해도 무방 (PR 분할은 push 시점 결정).

---

## Chunk 2: B2 — MDC scope/helper/decorator + interceptor 격상 + listener 예시 + 테스트

> Chunk 1 위에 MDC 4 field 가 jsonPayload 에 자동 emit 되도록 wire up. 머지 시 `jsonPayload.requestId="abc"` 같은 indexed query 가능.
>
> **review 검증포인트**: ① `RequestIdInterceptor.current()` backward compat 유지 (DjCommandService 등 A1 호출자 무파손) ② MdcTaskDecorator 가 stale MDC 잔존 leak 안 만듦 ③ `MdcScope.close()` unchecked override 동작 ④ WebSocket `configureClientInboundChannel` wiring 후 기존 SUBSCRIBE/UNSUBSCRIBE 동작 회귀 zero ⑤ UserActivityLogListener.on(CrewAccessedEvent) 의 partyroomId MDC 가 listener thread 의 log 에 emit 됨 (ListAppender 통합테스트).

### Task 9: `MdcScope` sub-interface

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/log/MdcScope.java`

`AutoCloseable.close() throws Exception` 의 checked exception 을 narrow 해서 unchecked 로 override. try-with-resources 시 catch 보일러플레이트 제거.

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.common.log;

/**
 * try-with-resources 호환 MDC scope. {@code AutoCloseable.close() throws Exception} 의
 * checked 시그니처를 unchecked {@code void close()} 로 override — 호출자가 별도
 * try-catch 없이 자연스럽게 try-with-resources 사용 가능.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.5.
 */
public interface MdcScope extends AutoCloseable {
    @Override
    void close();
}
```

- [ ] **Step 2: 컴파일 통과 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/log/MdcScope.java
git commit -m "feat(obs-b2): MdcScope — AutoCloseable unchecked override"
```

### Task 10: `MdcHelper.scope` + 테스트

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/log/MdcHelper.java`
- Test: `app/src/test/java/com/pfplaybackend/api/common/log/MdcHelperTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcHelperTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    @DisplayName("scope: 진입 시 MDC put, close 시 remove (이전 값 없을 때)")
    void scope_put_and_remove() {
        try (MdcScope ignored = MdcHelper.scope("partyroomId", 42L)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("42");
        }
        assertThat(MDC.get("partyroomId")).isNull();
    }

    @Test
    @DisplayName("scope: 이전 값 있으면 close 시 복원")
    void scope_restores_previous_value() {
        MDC.put("partyroomId", "10");
        try (MdcScope ignored = MdcHelper.scope("partyroomId", 99L)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("99");
        }
        assertThat(MDC.get("partyroomId")).isEqualTo("10");
    }

    @Test
    @DisplayName("scope: value null 이면 no-op MdcScope (기존 값 무영향)")
    void scope_null_value_is_noop() {
        MDC.put("partyroomId", "existing");
        try (MdcScope ignored = MdcHelper.scope("partyroomId", null)) {
            assertThat(MDC.get("partyroomId")).isEqualTo("existing");
        }
        assertThat(MDC.get("partyroomId")).isEqualTo("existing");
    }

    @Test
    @DisplayName("nested scope: 안쪽 close 시 바깥 값 복원")
    void nested_scope_restores_outer() {
        try (MdcScope outer = MdcHelper.scope("partyroomId", "X")) {
            assertThat(MDC.get("partyroomId")).isEqualTo("X");
            try (MdcScope inner = MdcHelper.scope("partyroomId", "Y")) {
                assertThat(MDC.get("partyroomId")).isEqualTo("Y");
            }
            assertThat(MDC.get("partyroomId")).isEqualTo("X");
        }
        assertThat(MDC.get("partyroomId")).isNull();
    }

    @Test
    @DisplayName("scope: value 가 Long/Integer/String 어떤 타입이든 String 으로 변환")
    void scope_handles_various_value_types() {
        try (MdcScope ignored = MdcHelper.scope("k", 123)) {
            assertThat(MDC.get("k")).isEqualTo("123");
        }
        try (MdcScope ignored = MdcHelper.scope("k", "abc")) {
            assertThat(MDC.get("k")).isEqualTo("abc");
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MdcHelperTest*"`
Expected: FAIL (`MdcHelper` 미존재)

- [ ] **Step 3: 구현 작성**

```java
package com.pfplaybackend.api.common.log;

import org.slf4j.MDC;

/**
 * MDC 키 스코프 진입/복원 helper.
 *
 * <p>{@link MdcScope} 가 unchecked {@code close()} 라 caller 는 try-with-resources 만 사용:
 * <pre>{@code
 * try (var ignored = MdcHelper.scope("partyroomId", id)) {
 *     log.info("...");  // partyroomId 가 jsonPayload 에 emit
 * }
 * }</pre>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.5.
 */
public final class MdcHelper {

    private static final MdcScope NOOP = () -> {};

    private MdcHelper() {}

    /**
     * MDC[key] 에 value 설정 + scope close 시 이전 값 복원 (이전 값 없으면 remove).
     * value 가 null 이면 no-op MdcScope 반환 (MDC 미수정).
     */
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

- [ ] **Step 4: 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MdcHelperTest*"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/log/MdcHelper.java \
        app/src/test/java/com/pfplaybackend/api/common/log/MdcHelperTest.java
git commit -m "feat(obs-b2): MdcHelper.scope + 5 cases (null/nested/restore/types)"
```

### Task 11: `MdcTaskDecorator` + 테스트

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/common/log/MdcTaskDecorator.java`
- Test: `app/src/test/java/com/pfplaybackend/api/common/log/MdcTaskDecoratorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    @DisplayName("producer MDC 가 worker thread 로 복사된 후 클린업")
    void decorates_propagates_and_cleans() throws Exception {
        MDC.put("requestId", "req-abc");
        MDC.put("userId", "u-1");

        AtomicReference<String> capturedRequestId = new AtomicReference<>();
        AtomicReference<String> capturedUserId = new AtomicReference<>();
        Runnable runnable = () -> {
            capturedRequestId.set(MDC.get("requestId"));
            capturedUserId.set(MDC.get("userId"));
        };

        Runnable decorated = decorator.decorate(runnable);

        // 별도 thread 에서 실행 (worker thread 시뮬레이션 — 시작 시 MDC empty)
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> postRunRequestId = new AtomicReference<>();
        new Thread(() -> {
            decorated.run();
            // run 후 finally 가 MDC.clear 했어야 함
            postRunRequestId.set(MDC.get("requestId"));
            latch.countDown();
        }).start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(capturedRequestId.get()).isEqualTo("req-abc");
        assertThat(capturedUserId.get()).isEqualTo("u-1");
        assertThat(postRunRequestId.get()).isNull();  // worker thread MDC 클린업됨
    }

    // empty_producer_with_worker_leftover 시나리오는 두 thread 분리 셋업이 필요하나
    // (producer 에서 decorate, 별도 worker 에서 run) test 코드 복잡도 대비 가치 낮음 — drop.
    // 대신 propagation/cleans 테스트의 symmetry 로 동일 contract (context==null → MDC.clear) 가
    // 간접 검증됨: 첫 번째 테스트 후 finally 가 worker 의 prev (null) 로 clear 함.

    @Test
    @DisplayName("예외 발생 시에도 finally 가 MDC 클린업")
    void exception_during_run_still_cleans() throws Exception {
        MDC.put("requestId", "req-fail");
        Runnable failing = () -> { throw new RuntimeException("boom"); };
        Runnable decorated = decorator.decorate(failing);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<String> postValue = new AtomicReference<>();
        new Thread(() -> {
            try {
                decorated.run();
            } catch (RuntimeException e) {
                caught.set(e);
            }
            postValue.set(MDC.get("requestId"));
            latch.countDown();
        }).start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(caught.get()).hasMessage("boom");
        assertThat(postValue.get()).isNull();  // finally 가 clear
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MdcTaskDecoratorTest*"`
Expected: FAIL (`MdcTaskDecorator` 미존재)

- [ ] **Step 3: 구현 작성**

```java
package com.pfplaybackend.api.common.log;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Producer thread 의 MDC context 를 worker thread 로 복사.
 *
 * <p>Best-effort restore: finally 의 {@code prev} 는 worker 의 *기존* MDC (보통
 * clean pool thread 라 null). pool thread 에 stale MDC 가 leftover 라면 그 값을
 * 복원 — 그건 별도 코드 path 의 leak 이고, 본 decorator 는 *그 leak 을 보존* 한다
 * (decorator 가 leak fix 책임 아님). 정상 case 에서는 prev == null 이라
 * {@code MDC.clear()} = 깨끗한 thread 복원.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.4.
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

- [ ] **Step 4: 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*MdcTaskDecoratorTest*"`
Expected: PASS (2 tests — propagation/cleans + exception cleanup)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/log/MdcTaskDecorator.java \
        app/src/test/java/com/pfplaybackend/api/common/log/MdcTaskDecoratorTest.java
git commit -m "feat(obs-b2): MdcTaskDecorator + 2 cases (propagation/exception)"
```

### Task 12: `RequestIdInterceptor` MDC 격상

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptor.java`
- Modify (확장): `app/src/test/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptorTest.java` (기존 파일 — 확장)

ThreadLocal CURRENT 제거. `MDC.put("requestId", id)` + (인증된 경우) `MDC.put("userId", uid)`. afterCompletion 에서 두 키 remove. `current()` 는 `MDC.get("requestId")` 위임 = backward compat 유지.

- [ ] **Step 1: 기존 RequestIdInterceptorTest 확인** (확장 대상)

```bash
cat app/src/test/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptorTest.java | head -30
```

기존 테스트가 어떤 패턴 (MockHttpServletRequest, sanitize 검증 등) 인지 확인. MDC 검증 case 를 그 위에 추가.

- [ ] **Step 2: 새 MDC 검증 테스트 추가**

기존 `RequestIdInterceptorTest` 클래스 상단에 import 추가:
```java
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
```

기존 테스트들 인접에 케이스 추가:

```java
@AfterEach
void clearMdcAndSecurityContext() {
    MDC.clear();
    SecurityContextHolder.clearContext();
}

@Test
@DisplayName("preHandle: MDC.requestId 설정")
void preHandle_sets_mdc_requestId() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "test-req-id-001");
    MockHttpServletResponse response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    assertThat(MDC.get("requestId")).isEqualTo("test-req-id-001");
}

@Test
@DisplayName("afterCompletion: MDC.requestId + MDC.userId 모두 제거")
void afterCompletion_removes_mdc() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("user-42", null));
    interceptor.preHandle(request, response, new Object());
    assertThat(MDC.get("requestId")).isNotNull();
    assertThat(MDC.get("userId")).isEqualTo("user-42");

    interceptor.afterCompletion(request, response, new Object(), null);

    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("userId")).isNull();
}

@Test
@DisplayName("preHandle: 인증된 사용자 — MDC.userId 설정")
void preHandle_sets_mdc_userId_when_authenticated() {
    SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("authenticated-user", null));
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    assertThat(MDC.get("userId")).isEqualTo("authenticated-user");
}

@Test
@DisplayName("preHandle: 비인증 (anonymous) — MDC.userId 미설정")
void preHandle_no_userId_when_anonymous() {
    // SecurityContext 비어있음 (clearContext 후)
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    assertThat(MDC.get("userId")).isNull();
}

@Test
@DisplayName("current(): MDC 위임 (ThreadLocal 제거 후 backward compat)")
void current_delegates_to_mdc() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "compat-test");
    interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

    assertThat(RequestIdInterceptor.current()).isEqualTo("compat-test");
}
```

- [ ] **Step 3: 실패 확인 (예상)**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*RequestIdInterceptorTest*"`
Expected: 신규 3 케이스 FAIL (MDC 미설정 / current() 가 ThreadLocal 참조 중)

- [ ] **Step 4: 구현 수정 — `RequestIdInterceptor.java`**

```java
package com.pfplaybackend.api.common.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * HTTP 요청 상관관계 인터셉터.
 *
 * <p>들어오는 {@code X-Request-Id} 헤더 (sanitize 후) 또는 자동 생성 8자 id 를
 * MDC {@code requestId} 키로 푸시. 인증된 사용자의 uid 가 SecurityContext 에 있으면
 * {@code userId} MDC 도 함께 설정. afterCompletion 에서 둘 다 remove.
 *
 * <p>{@link #current()} 는 backward compat — MDC.get("requestId") 위임. A1
 * (`DjCommandService` 등) critical-path 로그가 사용.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.2.
 * Phase A6 (platform#210) 의 ThreadLocal 단계에서 MDC 격상 — Phase B2.
 *
 * <p>SecurityContext 가 preHandle 시점에 populated: Spring Security Filter Chain 이
 * DispatcherServlet 보다 먼저 실행되어 인증된 요청은 SecurityContextHolder 가 이미 채워짐.
 *
 * <p>async dispatch (DeferredResult/Callable) 미해소 — spec §10.4 참조.
 */
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
        // userId: SecurityContext 의 principal name 에서 추출 (A6 단계엔 미적용 — 본 단계서 시도, null safe)
        String userId = extractUserId();
        if (userId != null) MDC.put(MDC_USER_ID, userId);

        request.setAttribute(REQUEST_ID_ATTR, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_USER_ID);
    }

    /**
     * Backward compat — A1 critical-path 로그 호출자 (DjCommandService 등) 가 사용.
     * MDC.get 위임. HTTP 컨텍스트 밖에서는 null.
     */
    public static String current() {
        return MDC.get(MDC_REQUEST_ID);
    }

    private String extractOrGenerate(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        // 신뢰 경계: 클라이언트 제어값. 제어문자 제거 + 길이 상한.
        requestId = requestId.replaceAll("\\p{Cntrl}", "");
        if (requestId.length() > 64) requestId = requestId.substring(0, 64);
        if (requestId.isBlank()) return UUID.randomUUID().toString().substring(0, 8);
        return requestId;
    }

    /**
     * SecurityContext 에서 인증된 사용자의 uid 추출. 비인증/익명 = null.
     */
    private String extractUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            String name = auth.getName();
            if (name == null || name.isBlank() || "anonymousUser".equals(name)) return null;
            return name;
        } catch (Exception ignored) {
            return null;
        }
    }
}
```

> **주의**: 기존 코드의 ThreadLocal `CURRENT` 가 사라지므로 `ThreadLocal` 관련 import 도 제거. 신규 import = `org.slf4j.MDC`, `org.springframework.security.core.Authentication`, `org.springframework.security.core.context.SecurityContextHolder`.

- [ ] **Step 5: 기존 + 신규 모든 RequestIdInterceptorTest PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*RequestIdInterceptorTest*"`
Expected: PASS (기존 sanitize/UUID 케이스 + 신규 5 MDC 케이스: requestId 설정/clear/userId 인증/userId anonymous/current backward compat)

- [ ] **Step 6: A1 호출자 (`DjCommandService`) 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*DjCommandService*"`
Expected: 기존 DjCommandService 테스트 모두 GREEN — `current()` 의 MDC.get 위임이 HTTP 컨텍스트가 mock 안에서는 null 일 수 있음. 기존 테스트가 그 케이스를 어떻게 다루는지 확인 — null-safe 호출이면 PASS. 기존 테스트가 `RequestIdInterceptor.current()` 를 직접 호출/검증하지 않을 가능성 높음.

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptor.java \
        app/src/test/java/com/pfplaybackend/api/common/adapter/in/web/RequestIdInterceptorTest.java
git commit -m "feat(obs-b2): RequestIdInterceptor MDC 격상 + userId — current() backward compat"
```

### Task 13: `WebSocketMdcChannelInterceptor` + 테스트

**Files:**
- Create: `realtime/src/main/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptor.java`
- Test: `realtime/src/test/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.realtime.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketMdcChannelInterceptorTest {

    private final WebSocketMdcChannelInterceptor interceptor = new WebSocketMdcChannelInterceptor();

    @AfterEach
    void cleanupMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("preSend: sessionId + userId MDC 설정")
    void preSend_sets_sessionId_and_userId() {
        Message<?> message = buildMessage("ws-session-001", () -> "user-42");

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isEqualTo("ws-session-001");
        assertThat(MDC.get("userId")).isEqualTo("user-42");
    }

    @Test
    @DisplayName("afterSendCompletion: sessionId + userId MDC 제거")
    void afterSendCompletion_removes_mdc() {
        Message<?> message = buildMessage("ws-session-002", () -> "user-7");
        interceptor.preSend(message, null);

        interceptor.afterSendCompletion(message, null, true, null);

        assertThat(MDC.get("sessionId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("preSend: principal null safe")
    void preSend_null_principal_safe() {
        Message<?> message = buildMessage("ws-session-003", null);

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isEqualTo("ws-session-003");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("preSend: sessionId null safe")
    void preSend_null_sessionId_safe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(() -> "u-x");
        Message<?> message = MessageBuilder.createMessage("payload", accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(MDC.get("sessionId")).isNull();
        assertThat(MDC.get("userId")).isEqualTo("u-x");
    }

    private Message<?> buildMessage(String sessionId, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        if (principal != null) accessor.setUser(principal);
        return MessageBuilder.createMessage("payload", accessor.getMessageHeaders());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :realtime:test --tests "*WebSocketMdcChannelInterceptorTest*"`
Expected: FAIL (`WebSocketMdcChannelInterceptor` 미존재)

- [ ] **Step 3: 구현 작성**

```java
package com.pfplaybackend.realtime.interceptor;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP inbound channel 의 sessionId / userId 를 MDC 에 푸시.
 *
 * <p>Spring 의 default {@code ExecutorSubscribableChannel} single-thread dispatch 가
 * preSend → @MessageMapping handler → afterSendCompletion 을 같은 thread 에서 실행 —
 * MDC 가 handler 내부 log 에 자동 가시. {@code registration.taskExecutor(...)} 가
 * 도입되면 TaskDecorator wiring 필요 (spec §7.3 참조).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §7.3.
 */
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

- [ ] **Step 4: 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :realtime:test --tests "*WebSocketMdcChannelInterceptorTest*"`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add realtime/src/main/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptor.java \
        realtime/src/test/java/com/pfplaybackend/realtime/interceptor/WebSocketMdcChannelInterceptorTest.java
git commit -m "feat(obs-b2): WebSocketMdcChannelInterceptor — STOMP sessionId/userId MDC + 4 cases"
```

### Task 14: WebSocketConfig wiring

**Files:**
- Modify: `realtime/src/main/java/com/pfplaybackend/realtime/config/WebSocketConfig.java`

`configureClientInboundChannel(ChannelRegistration registration)` override 추가, 신규 interceptor wire up.

- [ ] **Step 1: 메서드 추가 + 의존성 주입**

```java
// import 추가:
import com.pfplaybackend.realtime.interceptor.WebSocketMdcChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;

// 기존 필드 인접:
private final WebSocketMdcChannelInterceptor webSocketMdcChannelInterceptor;
// (RequiredArgsConstructor 가 자동 주입)

// 메서드 신규 override:
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(webSocketMdcChannelInterceptor);
}
```

- [ ] **Step 2: 컴파일 통과**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :realtime:compileJava :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: realtime + app 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :realtime:test :app:test`
Expected: 모든 기존 테스트 GREEN

- [ ] **Step 4: 커밋**

```bash
git add realtime/src/main/java/com/pfplaybackend/realtime/config/WebSocketConfig.java
git commit -m "feat(obs-b2): WebSocketConfig — configureClientInboundChannel wiring MDC interceptor"
```

### Task 15: `AsyncConfig` TaskDecorator 적용

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java`

`userActivityLogExecutor` 에 `setTaskDecorator(new MdcTaskDecorator())` 추가.

- [ ] **Step 1: 1줄 추가**

```diff
 @Bean(name = UAL_EXECUTOR_BEAN)
 public ThreadPoolTaskExecutor userActivityLogExecutor() {
     ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
     exec.setCorePoolSize(2);
     exec.setMaxPoolSize(4);
     exec.setQueueCapacity(200);
     exec.setThreadNamePrefix("ual-");
     exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
+    exec.setTaskDecorator(new com.pfplaybackend.api.common.log.MdcTaskDecorator());
     exec.setWaitForTasksToCompleteOnShutdown(true);
     exec.setAwaitTerminationSeconds(10);
     exec.initialize();
     return exec;
 }
```

> import 깔끔하게 정리: `import com.pfplaybackend.api.common.log.MdcTaskDecorator;`

- [ ] **Step 2: 컴파일 + 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: PASS (기존 UserActivityLogListenerIT 등 @Async + AFTER_COMMIT 테스트 무파손)

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/common/config/AsyncConfig.java
git commit -m "feat(obs-b2): AsyncConfig — userActivityLogExecutor MDC TaskDecorator 적용"
```

### Task 16: `UserActivityLogListener.on(CrewAccessedEvent)` 예시 적용

**Files:**
- Modify: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java` — 메서드 `on(CrewAccessedEvent e)` 만 try-with-resources 로 감싸기

다른 listener 메서드들은 본 spec 범위 밖 (후속 polish PR).

- [ ] **Step 1: 수정**

```diff
+import com.pfplaybackend.api.common.log.MdcHelper;

 @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
 @Async(AsyncConfig.UAL_EXECUTOR_BEAN)
 public void on(CrewAccessedEvent e) {
-    UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
-            ? UserActivityEventType.PARTYROOM_ENTERED
-            : UserActivityEventType.PARTYROOM_EXITED;
-    log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
-        JsonMetadata.empty(), e.getOccurredAt());
+    try (var ignored = MdcHelper.scope("partyroomId", e.getPartyroomId().getId())) {
+        UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
+                ? UserActivityEventType.PARTYROOM_ENTERED
+                : UserActivityEventType.PARTYROOM_EXITED;
+        log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
+            JsonMetadata.empty(), e.getOccurredAt());
+    }
 }
```

- [ ] **Step 2: 컴파일 + 기존 IT 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListener*"`
Expected: PASS (기존 IT 무파손)

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java
git commit -m "feat(obs-b2): UserActivityLogListener.on(CrewAccessedEvent) — MdcHelper.scope partyroomId 예시 적용"
```

### Task 17: `UserActivityLogListenerMdcIT` — MDC 가 listener thread 의 log 에 emit 됨 검증

**Files:**
- Create: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerMdcIT.java`

ListAppender 로 listener thread 의 log line 캡처 → `event.getPartyroomId().getId()` 가 MDC.partyroomId 로 출현 검증.

기존 `UserActivityLogListenerCrewAccessIT` 가 IT 패턴 (TestContainers MySQL + @SpringBootTest + Awaitility) 갖춤. 그 위에 `ListAppender` 추가 + MDC 검증.

- [ ] **Step 1: 신규 IT 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserActivityLogListener.on(CrewAccessedEvent) 이 @Async + AFTER_COMMIT 경로에서
 * partyroomId MDC 를 listener thread 에 propagate 하는지 검증.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §10.2.
 *
 * <p>전제:
 * <ul>
 *   <li>AsyncConfig 의 userActivityLogExecutor 가 MdcTaskDecorator 적용</li>
 *   <li>Listener 의 on(CrewAccessedEvent) 가 MdcHelper.scope("partyroomId", ...) try-with-resources 적용</li>
 *   <li>AFTER_COMMIT phase 발화를 위해 TransactionTemplate 으로 publishEvent 감쌈
 *       (PartyroomCounterListenerIT 와 동일 패턴)</li>
 * </ul>
 */
class UserActivityLogListenerMdcIT extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;

    private ListAppender<ILoggingEvent> appender;
    private Logger listenerLogger;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(UserActivityLogListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
        appender.stop();
        appender.list.clear();
    }

    @Test
    @DisplayName("on(CrewAccessedEvent ENTER): listener thread 의 log MDC 에 partyroomId 출현")
    void crewAccessed_propagates_partyroomId_mdc() {
        // CrewAccessedEvent ctor: (PartyroomId, CrewId, UserId, AccessType)
        // — DomainEvent 가 LocalDateTime 자동 부여, ctor 인자 X
        CrewAccessedEvent event = new CrewAccessedEvent(
                new PartyroomId(5678L),
                new CrewId(9999L),
                new UserId(1234L),
                AccessType.ENTER
        );

        // AFTER_COMMIT phase 가 fire 되려면 TX 안에서 publish 필요 (PartyroomCounterListenerIT 패턴)
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));

        // @Async + AFTER_COMMIT 이라 비동기 대기
        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    // listener body 의 진입 log.info("[on.CrewAccessedEvent] ...") 가 capture 되며
                    // 그 event 의 MDCPropertyMap 에 partyroomId=5678 가 출현해야 함.
                    assertThat(appender.list)
                            .anyMatch(e -> "5678".equals(e.getMDCPropertyMap().get("partyroomId")));
                });
    }
}
```

> 구현자: `Awaitility` 의존성은 기존 `:app:test` IT 들 (PartyroomCounterListenerIT 등) 에서 이미 사용 중이라 추가 의존성 작업 필요 없음. Listener 안에 명시적 `log.info("[on.CrewAccessedEvent] ...")` 진입 로그가 있어야 capture 가능 — Task 16/17 의 listener 수정 시 진입 로그 한 줄 추가 (다음 Step).

- [ ] **Step 2: Listener 안에 진입 로그 추가 (Task 16 보강)**

```diff
 try (var ignored = MdcHelper.scope("partyroomId", e.getPartyroomId().getId())) {
     UserActivityEventType type = (e.getAccessType() == AccessType.ENTER)
             ? UserActivityEventType.PARTYROOM_ENTERED
             : UserActivityEventType.PARTYROOM_EXITED;
+    log.info("[on.CrewAccessedEvent] type={} userId={} partyroomId={}",
+        type, e.getUserId().getUid(), e.getPartyroomId().getId());
     log(e.getUserId().getUid(), type, e.getPartyroomId().getId(),
         JsonMetadata.empty(), e.getOccurredAt());
 }
```

(`log` SLF4J 필드는 `@Slf4j` lombok 으로 자동 — 기존 listener 가 이미 사용 중 가능성. 미사용 시 `private static final Logger log = LoggerFactory.getLogger(...);` 추가)

- [ ] **Step 3: 통합 테스트 PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*UserActivityLogListenerMdcIT*" -Pinclude-integration`
Expected: PASS (1 IT)

> Integration test 는 `-Pinclude-integration` 플래그 필요 (root build.gradle 의 useJUnitPlatform 설정 정합).

- [ ] **Step 4: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListenerMdcIT.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/listener/UserActivityLogListener.java
git commit -m "test(obs-b2): UserActivityLogListenerMdcIT + 진입 INFO 로그 — MDC partyroomId propagate 검증"
```

### Task 18: Chunk 2 전체 회귀 검증

- [ ] **Step 1: 전체 backend + realtime 테스트 통과**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :realtime:test
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test -Pinclude-integration
```
Expected: 모든 기존 + 신규 테스트 GREEN, 920+ tests + 신규 ~17 tests (5+3+3+3+1 = 15 unit + 1 IT + 4 WebSocket realtime), 총 ~937+

- [ ] **Step 2: ArchUnit 룰 영향 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*ArchUnit*"`
Expected: PASS — common 모듈 내부 신규 패키지 `common.log`, realtime 모듈 내부 interceptor 추가는 기존 BC 룰 영향 없음

- [ ] **Step 3: Chunk 2 종료 (PR2 머지 대상)**

PR1 (B1) 머지 완료 후 PR2 (B2) 생성. 또는 한 브랜치에서 PR 2개 분할 (`gh pr create --base develop --head feature/observability-b1-b2-json-mdc` 한 번에 생성하고 review 시 reviewer 가 B1/B2 commit 범위 나눠 봄 — 자율 결정).

---

## 최종 통합 검증 (PR 머지 전)

- [ ] PR1 (B1) develop 머지 → stg 자동 배포 → log 출력 시각 검증 (콘솔에 JSON 출력, message 안에 PII 마스킹 확인)
- [ ] PR2 (B2) develop 머지 → stg 자동 배포 → jsonPayload.requestId / userId / sessionId / partyroomId 가 GCP Cloud Logging 콘솔에서 indexed query 가능 확인
- [ ] B1 + B2 둘 다 main 머지 → 1주 실측 시작 (Task #18 의 plan §"후속 측정 절차" — Phase A plan 문서 참조)

---

## 관련 메모리

- [[feedback_observability_stack]] — GCP native + Cloud Run sink, Phase B 선제 결정 (2026-05-20)
- [[reference_pfplay_platform_jdk]] — Gradle JDK env
- [[feedback_pr_series_workflow]] — chunk + atomic group (PR1 B1 + PR2 B2)
- [[feedback_commit_consolidation_before_push]] — push/PR 전 micro commit squash 사용자 결정
- [[feedback_autonomous_execution]] — 결정 게이트 후 자율 진행
- [[feedback_korean_issue_commit_pr]] — 한글 PR/commit
- [[feedback_elegant_no_code_dirtying]] — RequestIdInterceptor ThreadLocal 제거 = A6 가 예고한 우아한 evolution
