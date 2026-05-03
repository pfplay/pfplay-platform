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
}
