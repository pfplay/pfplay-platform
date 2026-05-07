package com.pfplaybackend.api.common.config.security.web.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.security.admin-origin-guard")
public class AdminOriginProperties {
    private boolean enabled = true;
    private List<String> allowed = List.of();
}
