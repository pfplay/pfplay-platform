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
import static org.mockito.ArgumentMatchers.anyList;
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

    private PushSubscriptionData sub(long id, String endpoint, String lang) {
        PushSubscriptionData s = PushSubscriptionData.create(id, endpoint, "p-" + id, "a-" + id, lang);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
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
    @DisplayName("pushEnabled=false면 구독 조회·발송을 전혀 하지 않는다")
    void disabled_noSend() {
        service().fanout(announcement(false));
        verifyNoInteractions(sender, repository);
    }

    @Test
    @DisplayName("활성 구독마다 lang별 payload로 발송하고, GONE 없으면 revoke 하지 않는다")
    void enabled_sendsPerSubscriptionWithLangPayload_noRevokeWhenNoGone() {
        PushSubscriptionData ko = sub(1L, "ep-ko", "KO");
        PushSubscriptionData en = sub(2L, "ep-en", "EN");
        when(repository.findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(ko, en));
        when(sender.send(any(), any(), any(), any())).thenReturn(WebPushSender.Result.OK);

        service().fanout(announcement(true));

        ArgumentCaptor<String> koPayload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("ep-ko"), eq("p-1"), eq("a-1"), koPayload.capture());
        assertThat(koPayload.getValue()).contains("한국어제목").contains("한국어본문");
        ArgumentCaptor<String> enPayload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("ep-en"), eq("p-2"), eq("a-2"), enPayload.capture());
        assertThat(enPayload.getValue()).contains("EnglishTitle").contains("EnglishBody");
        // GONE 없음 → bulk revoke 미호출
        verify(repository, never()).revokeByIds(anyList(), any());
    }

    @Test
    @DisplayName("GONE 응답 구독 id만 모아 단일 bulk revoke 한다 (per-row revoke 아님)")
    void gone_bulkRevokesGoneIdsOnly() {
        PushSubscriptionData ko = sub(1L, "ep-ko", "KO");
        PushSubscriptionData en = sub(2L, "ep-en", "EN");
        when(repository.findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(ko, en));
        when(sender.send(eq("ep-ko"), any(), any(), any())).thenReturn(WebPushSender.Result.OK);
        when(sender.send(eq("ep-en"), any(), any(), any())).thenReturn(WebPushSender.Result.GONE);

        service().fanout(announcement(true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(repository).revokeByIds(ids.capture(), eq(LocalDateTime.now(clock)));
        assertThat(ids.getValue()).containsExactly(2L); // GONE 인 en(id=2)만
    }

    @Test
    @DisplayName("keyset 페이지네이션 — 가득 찬 페이지면 다음 페이지(lastId 초과)를 이어 조회한다")
    void paginatesByKeysetUntilPartialPage() {
        PushFanoutService service = service();
        ReflectionTestUtils.setField(service, "pageSize", 2);
        PushSubscriptionData s1 = sub(1L, "ep-1", "KO");
        PushSubscriptionData s2 = sub(2L, "ep-2", "KO");
        PushSubscriptionData s3 = sub(3L, "ep-3", "KO");
        // page1: 가득 참(2개=pageSize) → 계속 / page2: 1개<pageSize → 종료
        when(repository.findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(s1, s2));
        when(repository.findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(2L), any()))
                .thenReturn(List.of(s3));
        when(sender.send(any(), any(), any(), any())).thenReturn(WebPushSender.Result.OK);

        service.fanout(announcement(true));

        verify(repository).findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(0L), any());
        verify(repository).findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(eq(2L), any());
        verify(sender, times(3)).send(any(), any(), any(), any());
    }
}
