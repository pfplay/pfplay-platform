package com.pfplaybackend.api.administration.adapter.out.event;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.application.service.SystemAnnouncementCommandService;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * V14 system-announcement Task 8 Step 1 — end-to-end IT verifying the
 * publish → AFTER_COMMIT TransactionalEventListener → STOMP broadcast chain.
 *
 * <p>Approach: {@code @SpyBean SimpMessagingTemplate} captures the
 * {@code convertAndSend("/sub/system/announcements", payload)} call emitted by
 * {@link AnnouncementBroadcaster#on(com.pfplaybackend.api.administration.domain.event.AnnouncementPublishedEvent)}.
 * This proves:
 * <ol>
 *   <li>{@link SystemAnnouncementCommandService#publish} commits the entity row,</li>
 *   <li>the {@code @TransactionalEventListener(AFTER_COMMIT)} fires on commit,</li>
 *   <li>the listener invokes {@code SimpMessagingTemplate.convertAndSend} with the spec
 *       topic and a fully-formed payload (snapshot, not late lookup).</li>
 * </ol>
 *
 * <p>We do not stand up a STOMP test client here: that requires the realtime
 * {@code WebSocketConfig} broker prefix (currently {@code /sub}) to also bind {@code /topic},
 * which is out of scope for this PR (the public-side broker prefix routing is a
 * follow-up if the FE chooses STOMP-over-WS for system announcements). The
 * spy-bean approach is sufficient to prove the broadcaster contract; the wire
 * transport itself is Spring framework code.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-03-system-announcement-design.md §6, §10.4
 * Plan: docs/superpowers/plans/2026-05-03-system-announcement.md Task 8 Step 1
 */
class AnnouncementBroadcasterIT extends AbstractIntegrationTest {

    private static final String TOPIC = "/sub/system/announcements";
    private static final Long ADMIN_USER_ID = 99710L;
    private static final String ADMIN_EMAIL = "broadcaster-it@x";

    @Autowired private SystemAnnouncementCommandService commandService;
    @Autowired private SystemAnnouncementRepository announcementRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;

    @SpyBean private SimpMessagingTemplate messagingTemplate;

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
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            announcementRepository.deleteAll();
            administratorRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("publish EVENT → AFTER_COMMIT listener → /sub/system/announcements 로 ANNOUNCEMENT_PUBLISHED payload broadcast")
    void publish_event_broadcasts_after_commit() {
        // when — publish in its own transaction (TransactionTemplate so listener fires on commit)
        Long announcementId = transactionTemplate.execute(status ->
                commandService.publish(
                        AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                        "공지 제목", "Title EN", "본문", "Body EN",
                        null, null, null,
                        administratorId));

        assertThat(announcementId).isNotNull();

        // then — listener fires AFTER_COMMIT, capture the broadcast call (timeout 2s)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, timeout(2000).atLeastOnce())
                .convertAndSend(eq(TOPIC), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("eventType", "ANNOUNCEMENT_PUBLISHED")
                .containsEntry("announcementId", announcementId)
                .containsEntry("type", "EVENT")
                .containsEntry("severity", "INFO")
                .containsEntry("titleKo", "공지 제목")
                .containsEntry("titleEn", "Title EN")
                .containsEntry("messageKo", "본문")
                .containsEntry("messageEn", "Body EN");
        assertThat(payload.get("sentAt")).isNotNull();
    }

    @Test
    @DisplayName("publish 후 별도 transaction 에서 cancel — PUBLISHED payload 는 발사 시점 snapshot 그대로 (cancelledAt 미반영)")
    void publish_then_cancel_published_payload_is_snapshot() {
        // when — publish + cancel in separate transactions so each AFTER_COMMIT listener fires
        Long announcementId = transactionTemplate.execute(status ->
                commandService.publish(
                        AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                        "Snap KO", "Snap EN", "M-KO", "M-EN",
                        null, null, null,
                        administratorId));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(messagingTemplate, atLeastOnce()).convertAndSend(eq(TOPIC), any(Object.class))
        );

        transactionTemplate.executeWithoutResult(status ->
                commandService.cancel(announcementId, administratorId));

        // then — 2 broadcasts captured: ANNOUNCEMENT_PUBLISHED + ANNOUNCEMENT_CANCELLED
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, timeout(2000).atLeastOnce())
                .convertAndSend(eq(TOPIC), payloadCaptor.capture());

        // The PUBLISHED payload must not have cancellation metadata — it was built before cancel
        Map<String, Object> publishedPayload = payloadCaptor.getAllValues().stream()
                .filter(p -> "ANNOUNCEMENT_PUBLISHED".equals(p.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ANNOUNCEMENT_PUBLISHED payload not captured"));

        assertThat(publishedPayload)
                .containsEntry("eventType", "ANNOUNCEMENT_PUBLISHED")
                .containsEntry("titleKo", "Snap KO")
                .doesNotContainKey("cancelledAt");
        assertThat(publishedPayload.get("sentAt")).isNotNull();

        Map<String, Object> cancelledPayload = payloadCaptor.getAllValues().stream()
                .filter(p -> "ANNOUNCEMENT_CANCELLED".equals(p.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ANNOUNCEMENT_CANCELLED payload not captured"));
        assertThat(cancelledPayload)
                .containsEntry("announcementId", announcementId);
        assertThat(cancelledPayload.get("cancelledAt")).isNotNull();
    }
}
