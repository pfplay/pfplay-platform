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
