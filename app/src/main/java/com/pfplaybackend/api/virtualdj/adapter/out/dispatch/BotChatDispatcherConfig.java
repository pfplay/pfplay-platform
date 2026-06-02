package com.pfplaybackend.api.virtualdj.adapter.out.dispatch;

import com.pfplaybackend.api.virtualdj.application.port.BotChatDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link BotChatDispatcher} 폴백 빈 설정.
 *
 * <p>{@code @Bean @ConditionalOnMissingBean} 팩토리 메서드 형식은 {@code @Configuration} 클래스 안에서만
 * 컴포넌트 스캔 순서에 독립적으로 동작한다. 컴포넌트 스캔 대상 클래스에 직접 붙인
 * {@code @ConditionalOnMissingBean} 은 스캔 순서(비결정적)에 따라 조건 평가 시점이 달라져
 * {@code NoUniqueBeanDefinitionException} 을 유발할 수 있다.
 *
 * <p>Chunk 5 에서 {@code @Service LlmChatTaskRunner implements BotChatDispatcher} 가 도입되면,
 * 컴포넌트 스캔이 {@code @Bean} 팩토리 메서드보다 먼저 처리되므로 이 폴백은 자동으로 등록되지 않는다.
 */
@Configuration
public class BotChatDispatcherConfig {

    @Bean
    @ConditionalOnMissingBean(BotChatDispatcher.class)
    public BotChatDispatcher noOpBotChatDispatcher() {
        return new NoOpBotChatDispatcher();
    }
}
