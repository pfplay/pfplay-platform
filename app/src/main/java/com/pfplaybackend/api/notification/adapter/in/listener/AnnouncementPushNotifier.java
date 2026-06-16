package com.pfplaybackend.api.notification.adapter.in.listener;

import com.pfplaybackend.api.administration.domain.event.AnnouncementPublishedEvent;
import com.pfplaybackend.api.common.config.AsyncConfig;
import com.pfplaybackend.api.notification.application.service.PushFanoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 공지 발행 AFTER_COMMIT → Web Push fan-out 디스패치 리스너.
 *
 * <p>이 bean은 dispatch만 담당하고, 실제 fan-out(+GONE prune dirty-update)은
 * 별도 {@link PushFanoutService#fanout}({@code @Transactional})에 위임한다.
 * @Async + @TransactionalEventListener 와 @Transactional 을 한 메서드에 함께 두면
 * proxy 충돌로 비동기 thread에서 TX가 열리지 않으므로, bean을 분리해 transactional
 * proxy가 정상 적용되도록 한다. ({@code AnnouncementBroadcaster} 컨벤션 mirror.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementPushNotifier {

    private final PushFanoutService fanoutService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async(AsyncConfig.WEB_PUSH_EXECUTOR_BEAN)
    public void on(AnnouncementPublishedEvent event) {
        fanoutService.fanout(event.entity());
    }
}
