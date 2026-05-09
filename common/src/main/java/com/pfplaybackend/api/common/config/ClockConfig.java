package com.pfplaybackend.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 모든 LocalDateTime / Instant 시각은 KST 기준으로 통일.
 *
 * 이 zone은 Dockerfile 의 TZ=Asia/Seoul 과 함께 동작 — 컨테이너 OS TZ 가
 * 우연히 KST 가 아니어도 Clock 자체가 KST 를 보장한다 (방어적 명시).
 *
 * 주의: LocalDateTime.now() (Clock 인자 없음) 호출은 여전히 JVM default zone 을
 * 따른다. 새 코드는 LocalDateTime.now(clock) 를 주입받아 사용할 것.
 */
@Configuration
public class ClockConfig {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
