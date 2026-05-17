package com.pfplaybackend.api.common.config.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * 기본 SimpleClientHttpRequestFactory(HttpURLConnection)는 PATCH 미지원이라
     * Vercel Edge Config 쓰기(PATCH)가 영구 실패한다. Java 11+ HttpClient 기반
     * JdkClientHttpRequestFactory 는 PATCH 를 native 지원한다.
     * (bugs/2026-05-15-vercel-edge-config-patch-method.md, pfplay-platform#211)
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(new JdkClientHttpRequestFactory());
    }
}
