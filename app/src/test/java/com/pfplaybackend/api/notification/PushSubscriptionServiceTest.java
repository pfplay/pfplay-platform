package com.pfplaybackend.api.notification;

import com.pfplaybackend.api.notification.adapter.out.persistence.PushSubscriptionRepository;
import com.pfplaybackend.api.notification.application.service.PushSubscriptionService;
import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    private static final Long user = 1001L;
    private static final Clock clock = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneId.of("UTC"));

    @Mock PushSubscriptionRepository repository;
    PushSubscriptionService service;

    private void initService() {
        service = new PushSubscriptionService(repository, clock);
    }

    @Test
    @DisplayName("subscribe — 신규 endpoint면 새 구독을 저장한다")
    void subscribeNewEndpointSaves() {
        // given
        initService();
        when(repository.findByEndpoint("ep-new")).thenReturn(Optional.empty());
        when(repository.save(any(PushSubscriptionData.class))).thenAnswer(inv -> {
            PushSubscriptionData data = inv.getArgument(0);
            ReflectionTestUtils.setField(data, "id", 42L);
            return data;
        });

        // when
        Long id = service.subscribe(user, "ep-new", "p256dh-1", "auth-1", "KO");

        // then
        assertThat(id).isEqualTo(42L);
        verify(repository).save(any(PushSubscriptionData.class));
    }

    @Test
    @DisplayName("subscribe — 폐기된 endpoint 재구독이면 새 row가 아닌 기존 row를 부활시킨다")
    void subscribeRevivesRevokedRow() {
        // given
        initService();
        PushSubscriptionData existing = PushSubscriptionData.create(user, "ep-1", "old-p256dh", "old-auth", "EN");
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.revoke(LocalDateTime.now(clock));
        assertThat(existing.isActive()).isFalse();
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when
        Long id = service.subscribe(user, "ep-1", "new-p256dh", "new-auth", "KO");

        // then
        assertThat(id).isEqualTo(7L);
        assertThat(existing.isActive()).isTrue();           // revokedAt cleared
        assertThat(existing.getAuth()).isEqualTo("new-auth"); // auth updated
        assertThat(existing.getP256dh()).isEqualTo("new-p256dh");
        verify(repository, never()).save(any());            // NOT a new row
    }

    @Test
    @DisplayName("subscribe — 같은 회원 활성 재구독이면 키를 갱신한다(새 row 없음)")
    void subscribeSameUserActiveRotatesKeys() {
        // given: 같은 회원이 해지 없이 재구독(브라우저 키 회전)
        initService();
        PushSubscriptionData existing = PushSubscriptionData.create(user, "ep-1", "old-p256dh", "old-auth", "EN");
        ReflectionTestUtils.setField(existing, "id", 7L);
        assertThat(existing.isActive()).isTrue();
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when
        Long id = service.subscribe(user, "ep-1", "rot-p256dh", "rot-auth", "KO");

        // then
        assertThat(id).isEqualTo(7L);
        assertThat(existing.getP256dh()).isEqualTo("rot-p256dh");
        assertThat(existing.getAuth()).isEqualTo("rot-auth");
        assertThat(existing.getLang()).isEqualTo("KO");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("subscribe — 폐기된 타인 row 재구독은 기기 hand-off로 허용(새 소유자로 부활)")
    void subscribeRevivesRevokedRowOfDifferentUserAsHandoff() {
        // given: 이전 소유자(otherUser)가 로그아웃하며 해지한 endpoint를 새 회원(user)이 재점유
        initService();
        Long otherUser = 2002L;
        PushSubscriptionData existing = PushSubscriptionData.create(otherUser, "ep-1", "old-p256dh", "old-auth", "EN");
        ReflectionTestUtils.setField(existing, "id", 7L);
        existing.revoke(LocalDateTime.now(clock));
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when
        Long id = service.subscribe(user, "ep-1", "new-p256dh", "new-auth", "KO");

        // then
        assertThat(id).isEqualTo(7L);
        assertThat(existing.isActive()).isTrue();
        assertThat(existing.getUserId()).isEqualTo(user);   // 새 소유자로 reassign
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("subscribe — 활성 + 타인 소유 endpoint 점유는 거부(IDOR 방어)")
    void subscribeRejectsActiveForeignEndpoint() {
        // given: 다른 회원이 활성으로 소유 중인 endpoint
        initService();
        Long otherUser = 2002L;
        PushSubscriptionData existing = PushSubscriptionData.create(otherUser, "ep-1", "p256dh", "auth", "EN");
        ReflectionTestUtils.setField(existing, "id", 7L);
        assertThat(existing.isActive()).isTrue();
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when / then
        assertThatThrownBy(() -> service.subscribe(user, "ep-1", "x", "y", "KO"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(existing.getUserId()).isEqualTo(otherUser); // 변조 안 됨
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("unsubscribe — 소유자가 해지하면 revoke 처리된다")
    void unsubscribeByOwnerRevokes() {
        // given
        initService();
        PushSubscriptionData existing = PushSubscriptionData.create(user, "ep-1", "p256dh", "auth", "KO");
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when
        service.unsubscribe(user, "ep-1");

        // then
        assertThat(existing.isActive()).isFalse();
        assertThat(existing.getRevokedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    @DisplayName("unsubscribe — 타인의 endpoint 해지는 no-op이다 (userId 스코프)")
    void unsubscribeOtherUserIsNoOp() {
        // given
        initService();
        Long otherUser = 2002L;
        PushSubscriptionData existing = PushSubscriptionData.create(otherUser, "ep-1", "p256dh", "auth", "KO");
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(repository.findByEndpoint("ep-1")).thenReturn(Optional.of(existing));

        // when
        service.unsubscribe(user, "ep-1");

        // then
        assertThat(existing.isActive()).isTrue(); // untouched
    }
}
