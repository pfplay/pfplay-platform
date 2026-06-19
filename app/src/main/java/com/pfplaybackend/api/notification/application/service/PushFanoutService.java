package com.pfplaybackend.api.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.application.port.WebPushSender;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공지 발행 → 전체 활성 구독자 Web Push fan-out.
 *
 * <p>{@code @Async} 디스패치 리스너({@code AnnouncementPushNotifier})가 호출하는 별도 bean.
 *
 * <p><b>오케스트레이터는 의도적으로 @Transactional 이 아니다</b>(web#404 B fast-follow): HTTP push I/O 를
 * write TX 안에서 돌리면 느린 네트워크 동안 DB 커넥션을 점유한다. 따라서
 * (1) 활성 구독을 <b>keyset 페이지네이션</b>으로 배치 로드(무제한 findAll 회피),
 * (2) HTTP 발송은 <b>TX 밖</b>에서 수행,
 * (3) GONE(404/410)은 id 를 모아 <b>단일 짧은 bulk revoke TX</b>로 정리(per-row dirty update 회피).
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

    /** fan-out 배치 크기. (테스트에서 override.) */
    int pageSize = 500;

    public void fanout(SystemAnnouncementData a) {
        if (!a.isPushEnabled()) return;
        int sent = 0, gone = 0, failed = 0;
        long lastId = 0L;
        List<Long> goneIds = new ArrayList<>();

        while (true) {
            List<PushSubscriptionData> page =
                    repository.findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(lastId, PageRequest.ofSize(pageSize));
            if (page.isEmpty()) break;
            for (PushSubscriptionData s : page) {
                // ⚠️ HTTP 는 write TX 밖에서 — 커넥션 장기 점유 방지.
                String payload = buildPayload(a, s.getLang());
                WebPushSender.Result r = sender.send(s.getEndpoint(), s.getP256dh(), s.getAuth(), payload);
                if (r == WebPushSender.Result.GONE) {
                    goneIds.add(s.getId());
                    gone++;
                } else if (r == WebPushSender.Result.OK) {
                    sent++;
                } else {
                    failed++;
                }
            }
            lastId = page.get(page.size() - 1).getId();
            if (page.size() < pageSize) break;
        }

        if (!goneIds.isEmpty()) {
            repository.revokeByIds(goneIds, LocalDateTime.now(clock));  // 단일 짧은 bulk revoke TX
        }
        // 휴면 기능 첫 활성화 관측용 — fan-out 결과를 남긴다.
        log.info("[web-push] announcement {} fan-out: ok={} gone(pruned)={} failed={}",
                a.getId(), sent, gone, failed);
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
