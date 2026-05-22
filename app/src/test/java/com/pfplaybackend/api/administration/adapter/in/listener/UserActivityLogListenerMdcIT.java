package com.pfplaybackend.api.administration.adapter.in.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserActivityLogListener.on(CrewAccessedEvent) 이 @Async + AFTER_COMMIT 경로에서
 * partyroomId MDC 를 listener thread 에 propagate 하는지 검증.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §10.2.
 *
 * <p>전제:
 * <ul>
 *   <li>AsyncConfig 의 userActivityLogExecutor 가 MdcTaskDecorator 적용</li>
 *   <li>Listener 의 on(CrewAccessedEvent) 가 MdcHelper.scope("partyroomId", ...) try-with-resources 적용</li>
 *   <li>AFTER_COMMIT phase 발화를 위해 TransactionTemplate 으로 publishEvent 감쌈
 *       (PartyroomCounterListenerIT 와 동일 패턴)</li>
 * </ul>
 */
class UserActivityLogListenerMdcIT extends AbstractIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;

    private ListAppender<ILoggingEvent> appender;
    private Logger listenerLogger;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(UserActivityLogListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
        appender.stop();
        appender.list.clear();
    }

    @Test
    @DisplayName("on(CrewAccessedEvent ENTER): listener thread 의 log MDC 에 partyroomId 출현")
    void crewAccessed_propagates_partyroomId_mdc() {
        // CrewAccessedEvent ctor: (PartyroomId, CrewId, UserId, AccessType)
        // — DomainEvent 가 LocalDateTime 자동 부여, ctor 인자 X
        CrewAccessedEvent event = new CrewAccessedEvent(
                new PartyroomId(5678L),
                new CrewId(9999L),
                new UserId(1234L),
                AccessType.ENTER
        );

        // AFTER_COMMIT phase 가 fire 되려면 TX 안에서 publish 필요 (PartyroomCounterListenerIT 패턴)
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));

        // @Async + AFTER_COMMIT 이라 비동기 대기
        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    // listener body 의 진입 log.info("[on.CrewAccessedEvent] ...") 가 capture 되며
                    // 그 event 의 MDCPropertyMap 에 partyroomId=5678 가 출현해야 함.
                    assertThat(appender.list)
                            .anyMatch(e -> "5678".equals(e.getMDCPropertyMap().get("partyroomId")));
                });
    }
}
