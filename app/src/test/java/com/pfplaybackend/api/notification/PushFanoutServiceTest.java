package com.pfplaybackend.api.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.value.AnnouncementSeverity;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.application.port.WebPushSender;
import com.pfplaybackend.api.notification.application.service.PushFanoutService;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushFanoutServiceTest {

    private static final Clock clock = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneId.of("UTC"));

    @Mock PushSubscriptionRepository repository;
    @Mock WebPushSender sender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PushFanoutService service() {
        return new PushFanoutService(repository, sender, objectMapper, clock);
    }

    private SystemAnnouncementData announcement(boolean pushEnabled) {
        SystemAnnouncementData a = SystemAnnouncementData.create(
                AnnouncementType.EVENT, AnnouncementSeverity.INFO,
                "한국어제목", "EnglishTitle", "한국어본문", "EnglishBody",
                null, null, null,
                LocalDateTime.now(clock), 1L, pushEnabled);
        ReflectionTestUtils.setField(a, "id", 99L);
        return a;
    }

    @Test
    @DisplayName("pushEnabled=false면 sender를 전혀 호출하지 않는다")
    void disabled_noSend() {
        // given
        PushFanoutService service = service();

        // when
        service.fanout(announcement(false));

        // then
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("pushEnabled=true면 활성 구독마다 lang별 payload로 발송한다")
    void enabled_sendsPerSubscriptionWithLangPayload() {
        // given
        PushFanoutService service = service();
        PushSubscriptionData ko = PushSubscriptionData.create(1L, "ep-ko", "p-ko", "a-ko", "KO");
        PushSubscriptionData en = PushSubscriptionData.create(2L, "ep-en", "p-en", "a-en", "EN");
        when(repository.findAllActive()).thenReturn(List.of(ko, en));
        when(sender.send(any(), any(), any(), any())).thenReturn(WebPushSender.Result.OK);

        // when
        service.fanout(announcement(true));

        // then
        ArgumentCaptor<String> koPayload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("ep-ko"), eq("p-ko"), eq("a-ko"), koPayload.capture());
        assertThat(koPayload.getValue()).contains("한국어제목").contains("한국어본문");

        ArgumentCaptor<String> enPayload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("ep-en"), eq("p-en"), eq("a-en"), enPayload.capture());
        assertThat(enPayload.getValue()).contains("EnglishTitle").contains("EnglishBody");
    }

    @Test
    @DisplayName("GONE 응답 구독은 revoke 되고, OK 구독은 활성 유지된다")
    void gone_revokesThatSubscriptionOnly() {
        // given
        PushFanoutService service = service();
        PushSubscriptionData ko = PushSubscriptionData.create(1L, "ep-ko", "p-ko", "a-ko", "KO");
        PushSubscriptionData en = PushSubscriptionData.create(2L, "ep-en", "p-en", "a-en", "EN");
        when(repository.findAllActive()).thenReturn(List.of(ko, en));
        when(sender.send(eq("ep-ko"), any(), any(), any())).thenReturn(WebPushSender.Result.OK);
        when(sender.send(eq("ep-en"), any(), any(), any())).thenReturn(WebPushSender.Result.GONE);

        // when
        service.fanout(announcement(true));

        // then
        assertThat(ko.isActive()).isTrue();   // OK → 유지
        assertThat(en.isActive()).isFalse();  // GONE → revoke
    }
}
