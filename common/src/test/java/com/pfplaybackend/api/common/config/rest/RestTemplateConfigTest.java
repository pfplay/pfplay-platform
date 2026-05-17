package com.pfplaybackend.api.common.config.rest;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RestTemplateConfigTest {

    /**
     * 근본 원인 회귀 방지: 기본 SimpleClientHttpRequestFactory(HttpURLConnection)는
     * PATCH 미지원이라 Vercel Edge Config 쓰기가 영구 실패한다
     * (bugs/2026-05-15-vercel-edge-config-patch-method.md, #211).
     * RestTemplate 빈은 PATCH 지원 팩토리(JdkClientHttpRequestFactory, Java 11+)를 써야 한다.
     */
    @Test
    void restTemplateBean_usesPatchCapableJdkClientHttpRequestFactory() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();

        assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
