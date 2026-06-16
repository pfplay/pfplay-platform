package com.pfplaybackend.api.notification.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * VAPID(Web Push, RFC 8291) 설정. fail-closed: enabled 기본 false.
 * 키 값은 배포 시 환경변수로 주입한다(코드/형상관리에 절대 커밋 금지).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.web-push")
public class WebPushProperties {

    private boolean enabled = false;
    private String publicKey;
    private String privateKey;
    private String subject = "mailto:admin@pfplay.io";
}
