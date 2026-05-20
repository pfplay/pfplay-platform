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

    // logback-spring.xml 의 <fieldNames> 와 cross-reference:
    //   <message>message</message>, <stackTrace>exception</stackTrace>.
    // XML rename 시 본 set 도 같이 변경 — LogbackJsonConfigTest 가 둘의 일치를 잠금.
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
            // 주의: KV 패턴들의 replacement 가 `$1=<redacted>` 라 입력 separator 가 `:` 였더라도 `=` 로 정규화됨.
            // 의도된 동작 — 토큰은 완전 마스킹되므로 보안 영향 zero, 로그 가독성도 일관됨.
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
