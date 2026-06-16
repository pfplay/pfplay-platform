package com.pfplaybackend.api.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.application.port.WebPushSender;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공지 발행 → 전체 활성 구독자 Web Push fan-out.
 *
 * <p>{@code @Async} 디스패치 리스너({@code AnnouncementPushNotifier})가 호출하는 별도 bean —
 * 비동기 thread에서 독립 TX를 열어, GONE(404/410) 구독을 revoke 한 dirty-update가
 * 트랜잭션 커밋 시점에 flush 되도록 보장한다. (리스너에 직접 @Transactional 을 붙이면
 * @Async + @TransactionalEventListener 와 충돌하므로 책임을 분리한다.)
 *
 * <p>{@link WebPushSender}는 fail-soft(예외 미발생)이므로 한 구독 실패가 fan-out 전체를
 * 중단하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushFanoutService {

    private final PushSubscriptionRepository repository;
    private final WebPushSender sender;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void fanout(SystemAnnouncementData a) {
        if (!a.isPushEnabled()) return;
        // 휴면 기능 첫 활성화 관측용 — fan-out 규모·결과를 남긴다.
        List<PushSubscriptionData> active = repository.findAllActive();
        int sent = 0, gone = 0, failed = 0;
        for (PushSubscriptionData s : active) {
            String payload = buildPayload(a, s.getLang());
            WebPushSender.Result r = sender.send(s.getEndpoint(), s.getP256dh(), s.getAuth(), payload);
            if (r == WebPushSender.Result.GONE) {
                s.revoke(LocalDateTime.now(clock));
                gone++;
            } else if (r == WebPushSender.Result.OK) {
                sent++;
            } else {
                failed++;
            }
        }
        log.info("[web-push] announcement {} fan-out: subs={} ok={} gone(pruned)={} failed={}",
                a.getId(), active.size(), sent, gone, failed);
    }

    private String buildPayload(SystemAnnouncementData a, String lang) {
        boolean ko = "KO".equalsIgnoreCase(lang);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", ko ? a.getTitleKo() : a.getTitleEn());
        body.put("body", ko ? a.getMessageKo() : a.getMessageEn());
        body.put("url", "/");
        body.put("tag", "announcement-" + a.getId());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
