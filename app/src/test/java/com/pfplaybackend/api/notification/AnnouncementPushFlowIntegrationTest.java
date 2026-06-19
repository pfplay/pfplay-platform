package com.pfplaybackend.api.notification;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementCommandService;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.application.port.WebPushSender;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase B 최상위 가드 IT — 공지 발행 → async fan-out → GONE(410/404) 구독 prune 영속.
 *
 * <p>증명 체인:
 * <ol>
 *   <li>{@link SystemAnnouncementCommandService#publish}(sendPush=true) 가 row 를 커밋,</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT) @Async(webPushExecutor)}
 *       리스너({@code AnnouncementPushNotifier})가 별도 thread 에서 발사,</li>
 *   <li>{@link com.pfplaybackend.api.notification.application.service.PushFanoutService#fanout}
 *       이 비동기 thread 에서 keyset 페이지네이션으로 활성 구독을 배치 로드하고(HTTP 는 write TX 밖),
 *       GONE 으로 보고된 구독 id 를 모아 {@code revokeByIds} 단일 bulk revoke TX 로 실제 DB 에 영속한다.</li>
 * </ol>
 *
 * <p>⚠️ AFTER_COMMIT 리스너는 publish TX 가 COMMIT 되어야만 발사되므로, publish 를
 * {@link TransactionTemplate} 안에서 호출한다(테스트 메서드 자체는 비-transactional —
 * {@link AbstractIntegrationTest} 에 @Transactional 없음). 자기 정리는 {@code @AfterEach}.
 *
 * <p>{@code @EnableAsync}({@code AsyncConfig}) 가 풀 컨텍스트에 활성이므로 리스너는
 * {@code webpush-} executor thread 에서 실행된다 → 진짜 cross-thread TX 경계를 검증한다.
 * (executor thread name 을 캡처해 발행 thread 와 다름을 단언한다.)
 */
class AnnouncementPushFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementPushFlowIntegrationTest.class);

    private static final Long ADMIN_USER_ID = 99720L;
    private static final String ADMIN_EMAIL = "push-flow-it@x";
    private static final String EP_OK = "ep-ok";
    private static final String EP_GONE = "ep-gone";

    @Autowired private SystemAnnouncementCommandService commandService;
    @Autowired private SystemAnnouncementRepository announcementRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockBean private WebPushSender sender;

    private Long administratorId;

    @BeforeEach
    void seed() {
        // FK fk_announcement_sent_by → administrator(administrator_id)
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(ADMIN_USER_ID), ADMIN_EMAIL, passwordEncoder.encode("secret123"));
        userAccountRepository.saveAndFlush(ua);
        AdministratorData admin = administratorRepository
                .saveAndFlush(AdministratorData.createSuperAdmin(ADMIN_USER_ID));
        this.administratorId = admin.getAdministratorId();

        // 2 active subscriptions — push_subscription 은 user FK 없음, 임의 userId 허용
        pushSubscriptionRepository.saveAndFlush(
                PushSubscriptionData.create(1001L, EP_OK, "p256dh-ok", "auth-ok", "KO"));
        pushSubscriptionRepository.saveAndFlush(
                PushSubscriptionData.create(1002L, EP_GONE, "p256dh-gone", "auth-gone", "EN"));
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            pushSubscriptionRepository.deleteAll();
            announcementRepository.deleteAll();
            administratorRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("publish EVENT(sendPush=true) → async fan-out → GONE 구독은 revoke 영속, OK 구독은 active 유지")
    void publish_with_push_prunes_gone_subscription() {
        // ep-gone → GONE(410/404) → prune, ep-ok → OK → keep.
        // thenAnswer 로 endpoint 별 결과 + 실행 thread name 을 함께 캡처한다.
        AtomicReference<String> executorThread = new AtomicReference<>();
        when(sender.send(any(), any(), any(), any())).thenAnswer(invocation -> {
            executorThread.set(Thread.currentThread().getName());
            String endpoint = invocation.getArgument(0);
            return EP_GONE.equals(endpoint) ? WebPushSender.Result.GONE : WebPushSender.Result.OK;
        });

        String publishThread = Thread.currentThread().getName();

        // when — publish in its own transaction so AFTER_COMMIT listener fires
        Long announcementId = transactionTemplate.execute(status ->
                commandService.publish(
                        AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                        "공지 제목", "Title EN", "본문", "Body EN",
                        null, null, null,
                        administratorId, true));
        assertThat(announcementId).isNotNull();

        // then — async fan-out (webpush- executor thread) 가 GONE 구독을 prune, 영속될 때까지 await
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            PushSubscriptionData gone = freshFind(EP_GONE);
            assertThat(gone).isNotNull();
            assertThat(gone.getRevokedAt()).as("GONE 구독은 revoke 되어야 함").isNotNull();
            assertThat(gone.isActive()).isFalse();
        });

        // OK 구독은 그대로 active
        PushSubscriptionData ok = freshFind(EP_OK);
        assertThat(ok).isNotNull();
        assertThat(ok.getRevokedAt()).isNull();
        assertThat(ok.isActive()).isTrue();

        // 2 구독 모두 발송 시도
        verify(sender, times(2)).send(any(), any(), any(), any());

        // cross-thread TX 경계 검증 finding: 리스너가 발행 thread 와 다른 webpush- thread 에서 실행됐는지
        String fanoutThread = executorThread.get();
        assertThat(fanoutThread).isNotNull();
        log.info("[push-flow-it] publishThread={} fanoutThread={}", publishThread, fanoutThread);
        assertThat(fanoutThread)
                .as("fan-out 은 @Async webpush- executor thread 에서 실행되어야 함 (publish thread 와 다름)")
                .startsWith("webpush-")
                .isNotEqualTo(publishThread);
    }

    @Test
    @DisplayName("publish EVENT(sendPush=false) → fan-out 미수행, sender 무호출, 두 구독 모두 active 유지")
    void publish_without_push_does_not_fan_out() {
        Long announcementId = transactionTemplate.execute(status ->
                commandService.publish(
                        AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                        "공지 제목", "Title EN", "본문", "Body EN",
                        null, null, null,
                        administratorId, false));
        assertThat(announcementId).isNotNull();

        // 잠시 대기 — 혹시 async 리스너가 발사되어 send 를 호출하면 잡아낸다
        Awaitility.await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(sender, never()).send(any(), any(), any(), any()));
        verifyNoInteractions(sender);

        // 두 구독 모두 active 유지
        assertThat(freshFind(EP_OK).isActive()).isTrue();
        assertThat(freshFind(EP_GONE).isActive()).isTrue();
    }

    /** 항상 DB 에서 새로 읽어 prune 의 영속 여부를 검증 (영속성 컨텍스트 1-level 캐시 우회). */
    private PushSubscriptionData freshFind(String endpoint) {
        return transactionTemplate.execute(status ->
                pushSubscriptionRepository.findByEndpoint(endpoint).orElse(null));
    }
}
