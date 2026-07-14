package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.party.adapter.in.listener.message.OutgoingGroupChatMessage;
import com.pfplaybackend.api.party.application.port.out.ChatPenaltyCachePort;
import com.pfplaybackend.api.party.application.service.chat.PartyroomChatCommandService;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가상 DJ P3a 봇 채팅 전달 경로 통합 검증 (LLM 미관여).
 *
 * <p>봇이 {@link PartyroomChatCommandService#sendMessageAsCrew} 로 발행한 채팅 1건이
 * <b>실제 Redis pub/sub + 실제 브로드캐스트 리스너</b>를 거쳐, 실제 방의 모든 유저가 구독하는
 * WebSocket 브로드캐스트 경계({@code /sub/partyrooms/{id}})까지 안정적으로 도달함을 증명한다.
 *
 * <pre>
 *  sendMessageAsCrew
 *    → ChatMessageDto.ofCrew(...)
 *    → RedisMessagePublisher.publish("chat_message_sent", dto)
 *    → Redis pub/sub  (스레드 경계 횡단)
 *    → GroupBroadcastTopicListener  (OutgoingGroupChatMessage 로 역직렬화)
 *    → SimpMessageSender.sendToGroup(roomId, msg)
 *    → SimpMessagingTemplate.convertAndSend("/sub/partyrooms/{roomId}", msg)  ← 여기서 캡처
 * </pre>
 *
 * <p>{@link AbstractIntegrationTest} 가 실 Redis 컨테이너 + {@code RedisListenerConfig} 의
 * {@code chat_message_sent → OutgoingGroupChatMessage} 리스너 + 실 {@link SimpMessagingTemplate}
 * 를 모두 로드한다. {@code @SpyBean} 으로 {@code convertAndSend} 호출을 캡처해
 * 페이로드의 crewId / content / eventType / partyroomId 무결성을 단언한다.
 *
 * <p>같은 채널의 두 번째 구독자(ChatMessageTopicListener → BotChatTrigger)는 독립적이며
 * 자체 루프 가드가 있어 본 검증(브로드캐스트 경계)에는 영향이 없다.
 */
class BotChatDeliveryIT extends AbstractIntegrationTest {

    @Autowired private PartyroomChatCommandService chatCommandService;

    @SpyBean private SimpMessagingTemplate messagingTemplate;
    @MockBean private ChatPenaltyCachePort chatPenaltyCachePort;

    @Test
    @DisplayName("봇 sendMessageAsCrew → Redis → /sub/partyrooms/{id} 브로드캐스트 (crewId+content+eventType 보존)")
    void botSendMessageAsCrew_isBroadcastToRoomTopic() {
        // given — 채팅밴 아님(가드 통과)
        when(chatPenaltyCachePort.isChatBanned(anyLong())).thenReturn(false);

        long roomId = 990001L;
        long crewId = 770002L;
        String content = "봇 인사 메시지";

        // when — 봇이 세션 없이 crewId 만으로 채팅 발행
        chatCommandService.sendMessageAsCrew(new PartyroomId(roomId), crewId, content);

        // then — 실 Redis pub/sub + 실 리스너를 거쳐 방 토픽으로 브로드캐스트되어야 한다(스레드 경계 → timeout 대기)
        String destination = "/sub/partyrooms/" + roomId;
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, timeout(3000).atLeastOnce())
                .convertAndSend(eq(destination), captor.capture());

        OutgoingGroupChatMessage broadcast = captor.getAllValues().stream()
                .filter(OutgoingGroupChatMessage.class::isInstance)
                .map(OutgoingGroupChatMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "OutgoingGroupChatMessage 가 /sub/partyrooms/" + roomId + " 로 브로드캐스트되지 않음. "
                                + "캡처된 값: " + captor.getAllValues()));

        // 페이로드 무결성 — ofCrew 발행값이 역직렬화 후에도 손실 없이 도달
        assertThat(broadcast.partyroomId().getId()).isEqualTo(roomId);
        assertThat(broadcast.eventType()).isEqualTo(MessageTopic.CHAT_MESSAGE_SENT);
        assertThat(broadcast.crew().crewId()).isEqualTo(crewId);
        assertThat(broadcast.message().content()).isEqualTo(content);
    }

    @Test
    @DisplayName("채팅밴 봇은 가드(isPossibleChat)에 막혀 브로드캐스트되지 않음 (end-to-end 가드 검증)")
    void bannedBot_isNotBroadcast() {
        // given — 채팅밴 상태
        when(chatPenaltyCachePort.isChatBanned(anyLong())).thenReturn(true);

        long roomId = 990003L;
        long crewId = 770004L;

        // when
        chatCommandService.sendMessageAsCrew(new PartyroomId(roomId), crewId, "차단되어야 할 메시지");

        // then — 발행 자체가 일어나지 않으므로 어떤 convertAndSend 도 이 방 토픽으로 가지 않는다
        verify(messagingTemplate, after(800).never())
                .convertAndSend(contains("/sub/partyrooms/" + roomId), any(Object.class));
    }
}
