package com.pfplaybackend.realtime.config;

import com.pfplaybackend.realtime.interceptor.WebSocketHandshakeInterceptor;
import com.pfplaybackend.realtime.interceptor.WebSocketMdcChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // STOMP heartbeat — a FAST-PATH for the common case only, NOT the ghost
    // upper bound. It detects a missed client heartbeat (5s interval), but a
    // dead client with no TCP FIN (mobile network death, app killed) only
    // surfaces after ~2 missed heartbeats → ~15-20s worst case (framework-
    // dependent), and can fail to ever fire. The AUTHORITATIVE upper bound on a
    // ghost crew is the PartyroomPresenceService reconcile cron
    // (@Scheduled fixedDelay 60s; threshold = now - maxGrace -
    // RECONCILE_BUFFER_SECONDS → forceOffline). Heartbeat just shortens the
    // common case; the cron — not heartbeat — guarantees eventual OFFLINE.
    //
    // NOTE: this does NOT replace the existing application-level heartbeat
    // (`/pub/heartbeat`, 4s interval) which is responsible for keeping the GCP
    // HTTP(S) LB connection alive (backend service timeout = 30s, confirmed via
    // `gcloud compute backend-services describe`). Past attempts to rely on STOMP
    // heartbeat alone for LB keep-alive did not work in practice — root cause not
    // verified, so the application heartbeat stays for keep-alive duties.
    private static final long SERVER_TO_CLIENT_HEARTBEAT_MS = 10_000L;
    private static final long CLIENT_TO_SERVER_HEARTBEAT_MS = 5_000L;

    private final WebSocketHandshakeInterceptor webSocketHandshakeInterceptor;
    private final WebSocketMdcChannelInterceptor webSocketMdcChannelInterceptor;

    @Bean
    public TaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry brokerRegistry) {
        brokerRegistry.enableSimpleBroker("/sub")
                .setHeartbeatValue(new long[] { SERVER_TO_CLIENT_HEARTBEAT_MS, CLIENT_TO_SERVER_HEARTBEAT_MS })
                .setTaskScheduler(stompHeartbeatScheduler());
        brokerRegistry.setApplicationDestinationPrefixes("/pub");
        brokerRegistry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketMdcChannelInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry endpointRegistry) {
        endpointRegistry
                .addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        return () -> attributes.get("uid").toString();
                    }
                })
                .addInterceptors(webSocketHandshakeInterceptor);
    }
}
