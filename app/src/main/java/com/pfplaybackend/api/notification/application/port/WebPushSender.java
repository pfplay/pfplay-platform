package com.pfplaybackend.api.notification.application.port;

/**
 * Web Push 발송 포트. 구현(martijndwars 어댑터 등)은 HTTP 결과를 OK/GONE/FAILED 로 정규화한다.
 * 절대 예외를 던지지 않는다(fail-soft) — 호출부는 Result 만 보고 prune/재시도를 판단한다.
 */
public interface WebPushSender {

    enum Result { OK, GONE, FAILED }   // GONE = 404/410 → prune

    Result send(String endpoint, String p256dh, String auth, String payloadJson);
}
