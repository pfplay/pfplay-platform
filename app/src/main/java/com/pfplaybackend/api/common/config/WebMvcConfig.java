package com.pfplaybackend.api.common.config;

import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 공통 설정.
 *
 * 현재는 {@link RequestIdInterceptor}(HTTP 상관관계, 옵저버빌리티 A6) 등록만 담당한다.
 * 신규 {@link WebMvcConfigurer} 가 코드베이스에 없어 새로 생성. (CORS 는 Spring
 * Security 체인에서 처리되므로 여기서 건드리지 않는다.)
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestIdInterceptor requestIdInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestIdInterceptor);
    }
}
