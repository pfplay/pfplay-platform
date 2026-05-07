package com.pfplaybackend.api.administration.adapter.out.edge.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "vercel.edge-config")
public class VercelEdgeConfigProperties {
    private String id;
    private String apiToken;
    private String teamId;
    private String baseUrl = "https://api.vercel.com";
    /**
     * Edge Config 안의 maintenance key 이름. 환경별 격리용 (Hobby plan 단일 인스턴스 제약).
     * prod = "maintenance" (default) / stg = "maintenance_preview" / dev = "maintenance_development"
     * frontend `VERCEL_ENV` 분기와 일치 (preview / development).
     */
    private String maintenanceKey = "maintenance";
}
