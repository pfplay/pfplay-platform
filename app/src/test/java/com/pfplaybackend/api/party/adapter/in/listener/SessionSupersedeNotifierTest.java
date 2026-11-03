package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.in.listener.message.SessionSupersededMessage;
import com.pfplaybackend.api.party.domain.event.SessionSupersededEvent;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.realtime.sender.SimpMessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * #369 밀려난 유저에게 SESSION_SUPERSEDED user-queue 알림을 발송하는 AFTER_COMMIT 리스너 단위 테스트.
 */
class SessionSupersedeNotifierTest {

    @Test
    @DisplayName("SessionSupersededEvent 수신 → 밀려난 유저(uid principal)의 개인 큐로 SESSION_SUPERSEDED 발송")
    void on_sends_session_superseded_to_pushed_out_user() {
        SimpMessageSender sender = mock(SimpMessageSender.class);
        SessionSupersedeNotifier notifier = new SessionSupersedeNotifier(sender);

        UserId user = new UserId(555L);
        SessionSupersededEvent event =
                new SessionSupersededEvent(user, new PartyroomId(10L), new PartyroomId(20L), 1234L);

        notifier.on(event);

        // 개인 큐 대상 = uid principal 문자열 (WS handshake uid 와 매칭)
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendToOneSession(eq("555"), payload.capture());

        SessionSupersededMessage msg = (SessionSupersededMessage) payload.getValue();
        assertThat(msg.type()).isEqualTo("SESSION_SUPERSEDED");
        assertThat(msg.newPartyroomId()).isEqualTo(20L);
        assertThat(msg.supersededPartyroomId()).isEqualTo(10L);
        assertThat(msg.occurredAt()).isEqualTo(1234L);
    }
}
