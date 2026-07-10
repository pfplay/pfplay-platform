package com.pfplaybackend.api.party.adapter.in.listener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pfplaybackend.api.common.config.redis.RedisMessagePublisher;
import com.pfplaybackend.api.common.domain.enums.MessageTopic;
import com.pfplaybackend.api.virtualcrew.adapter.in.listener.BotChatTrigger;
import com.pfplaybackend.api.virtualcrew.adapter.in.listener.ChatMessageTopicListener;
import com.pfplaybackend.api.party.adapter.in.listener.CrewProfilePreCheckTopicListener;
import com.pfplaybackend.api.party.adapter.in.listener.GroupBroadcastTopicListener;
import com.pfplaybackend.api.party.adapter.in.listener.PlaybackDurationWaitTopicListener;
import com.pfplaybackend.api.party.adapter.in.listener.PresenceExpirationListener;
import com.pfplaybackend.api.party.adapter.in.listener.message.*;
import com.pfplaybackend.api.party.application.port.out.ExpirationTaskPort;
import com.pfplaybackend.api.party.application.service.CrewProfileChangeEventHandler;
import com.pfplaybackend.api.party.application.service.PartyroomPresenceService;
import com.pfplaybackend.api.party.application.service.PlaybackCommandService;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.realtime.sender.SimpMessageSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;

import static java.util.Map.entry;

@Configuration
@RequiredArgsConstructor
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory,
                                                        SimpMessageSender simpMessageSender,
                                                        RedisTemplate<String, Object> redisTemplate,
                                                        DistributedLockExecutor distributedLockExecutor,
                                                        CrewProfileChangeEventHandler crewProfileService,
                                                        PlaybackCommandService playbackCommandService,
                                                        PartyroomPresenceService presenceService,
                                                        ExpirationTaskPort expirationTaskPort,
                                                        RedisMessagePublisher messagePublisher,
                                                        BotChatTrigger botChatTrigger,
                                                        ObjectMapper objectMapper) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Standard broadcast listeners: deserialize → sendToGroup
        Map.<String, Class<? extends GroupBroadcastMessage>>ofEntries(
                entry("chat_message_sent", OutgoingGroupChatMessage.class),
                entry("playback_deactivated", PartyroomDeactivationMessage.class),
                entry("crew_entered", CrewEnteredMessage.class),
                entry("crew_exited", CrewExitedMessage.class),
                entry("partyroom_closed", PartyroomClosedMessage.class),
                entry("reaction_performed", ReactionMotionMessage.class),
                entry("reaction_aggregation_updated", ReactionAggregationMessage.class),
                entry("crew_grade_changed", CrewGradeMessage.class),
                entry("crew_penalized", CrewPenaltyMessage.class),
                entry("crew_profile_changed", CrewProfileMessage.class),
                entry("dj_queue_changed", DjQueueChangeMessage.class),
                entry("playback_started", PlaybackStartMessage.class)
        ).forEach((topic, type) ->
                container.addMessageListener(
                        new GroupBroadcastTopicListener<>(simpMessageSender, objectMapper, type),
                        new ChannelTopic(topic)));

        // 가상 DJ 채팅 관찰 — chat_message_sent 토픽의 두 번째 구독자(WS 브로드캐스트와 독립).
        container.addMessageListener(
                new ChatMessageTopicListener(objectMapper, botChatTrigger),
                new ChannelTopic(MessageTopic.CHAT_MESSAGE_SENT.topic()));

        // Special listeners with business logic
        container.addMessageListener(new CrewProfilePreCheckTopicListener(objectMapper, distributedLockExecutor, crewProfileService, messagePublisher), new ChannelTopic("crew_profile_pre_check"));
        container.addMessageListener(new PlaybackDurationWaitTopicListener(expirationTaskPort, distributedLockExecutor, playbackCommandService), new PatternTopic("__keyevent@*__:expired"));
        container.addMessageListener(new PresenceExpirationListener(presenceService, distributedLockExecutor), new PatternTopic("__keyevent@*__:expired"));
        return container;
    }
}
