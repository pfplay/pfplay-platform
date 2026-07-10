package com.pfplaybackend.api.common;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * IT 전용 Redis(lettuce) 클라이언트 안정화 — 이슈 #320.
 *
 * <p><b>배경:</b> 전량 IT를 단일 JVM으로 도는 동안 Spring 컨텍스트 캐시가 누적되며(서로 다른 {@code @MockBean}
 * 조합마다 별도 컨텍스트), 컨텍스트마다 lettuce 가 자체 {@link ClientResources}(netty 이벤트루프 그룹)를
 * 생성 → 코어가 적은 CI 러너에서 netty 스레드 폭증·리소스 고갈로 커넥션이 불안정해진다. 이때
 * 부수적 Redis 명령이 lettuce <b>기본 commandTimeout 60초</b>를 블록하면
 * {@code PartyroomAccessCommandServiceRaceIT} 의 {@code done.await(60s)} latch 가 타임아웃한다.
 *
 * <p><b>수정(테스트 인프라 한정, 프로덕션 무변경):</b>
 * <ul>
 *   <li>모든 IT 컨텍스트가 <b>단일 정적 {@link ClientResources}</b>를 공유 → netty 이벤트루프가
 *       컨텍스트 수만큼 늘지 않는다(근원 완화).</li>
 *   <li>{@code commandTimeout} 을 <b>5초</b>로 bound → 순간 커넥션 이상 시 60초 hang 대신 fast-fail.
 *       (검증 대상 불변식 {@code crew_count==1} 은 DB atomic UPDATE + unique 제약으로 강제되며
 *       Redis 는 best-effort 라 안전하다.)</li>
 * </ul>
 *
 * <p>{@link AbstractIntegrationTest} 가 {@code @Import} 하여 전 IT 에 균일 적용한다.
 */
@TestConfiguration
public class TestRedisClientConfig {

    /**
     * 전 IT 컨텍스트 공유 정적 싱글톤. 커스터마이저로 주입만 하고 Spring 빈으로 등록하지 않으므로
     * 컨텍스트 종료 시 Spring 이 shutdown 하지 않는다(JVM 종료까지 유지). 컨텍스트마다 재생성 방지.
     */
    private static final ClientResources SHARED_CLIENT_RESOURCES = DefaultClientResources.create();

    @Bean
    LettuceClientConfigurationBuilderCustomizer lettuceItStabilityCustomizer() {
        return builder -> builder
                .clientResources(SHARED_CLIENT_RESOURCES)
                .commandTimeout(Duration.ofSeconds(5));
    }
}
