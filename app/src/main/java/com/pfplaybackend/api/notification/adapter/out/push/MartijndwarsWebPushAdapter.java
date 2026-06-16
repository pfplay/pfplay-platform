package com.pfplaybackend.api.notification.adapter.out.push;

import com.pfplaybackend.api.notification.application.port.WebPushSender;
import com.pfplaybackend.api.notification.config.WebPushProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Security;

/**
 * martijndwars web-push(5.1.1) 기반 {@link WebPushSender} 구현.
 * - enabled=false 이면 pushService 를 만들지 않고(fail-closed) send 는 FAILED 반환.
 * - HTTP status → Result 매핑: 2xx=OK, 404/410=GONE(구독 prune 대상), 그 외/예외=FAILED.
 * - 절대 예외를 전파하지 않는다(fail-soft).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MartijndwarsWebPushAdapter implements WebPushSender {

    private final WebPushProperties props;

    private PushService pushService;

    @PostConstruct
    void init() {
        if (!props.isEnabled()) {
            log.warn("[web-push] disabled (app.web-push.enabled=false) — 푸시 발송 비활성. fail-closed.");
            return;
        }
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            this.pushService = new PushService(props.getPublicKey(), props.getPrivateKey(), props.getSubject());
            log.info("[web-push] enabled — PushService initialized (subject={})", props.getSubject());
        } catch (Exception e) {
            // 키 형식 오류 등 — fail-closed 로 두고 발송은 FAILED 처리.
            log.error("[web-push] PushService init 실패 — 푸시 발송 비활성화. cause={}", e.toString());
            this.pushService = null;
        }
    }

    @Override
    public Result send(String endpoint, String p256dh, String auth, String payloadJson) {
        if (pushService == null) {
            return Result.FAILED;
        }
        try {
            Notification notification = new Notification(
                    endpoint, p256dh, auth, payloadJson.getBytes(StandardCharsets.UTF_8));
            HttpResponse response = pushService.send(notification);
            int statusCode = response.getStatusLine().getStatusCode();
            return mapStatus(statusCode);
        } catch (Exception e) {
            log.warn("[web-push] send 실패 — endpoint={}, cause={}", endpoint, e.toString());
            return Result.FAILED;
        }
    }

    /**
     * Push service HTTP status → Result 정규화.
     * 404/410 = 구독 만료/삭제(prune 대상) → GONE, 2xx = OK, 그 외 = FAILED.
     */
    public static Result mapStatus(int statusCode) {
        if (statusCode == 404 || statusCode == 410) {
            return Result.GONE;
        }
        if (statusCode >= 200 && statusCode < 300) {
            return Result.OK;
        }
        return Result.FAILED;
    }
}
