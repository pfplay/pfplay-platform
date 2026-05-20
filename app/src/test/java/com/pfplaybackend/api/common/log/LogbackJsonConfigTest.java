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
