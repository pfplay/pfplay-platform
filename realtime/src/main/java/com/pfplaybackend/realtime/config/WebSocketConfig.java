package com.pfplaybackend.realtime.config;

import com.pfplaybackend.realtime.interceptor.WebSocketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
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

    // STOMP heartbeat — used for fast disconnect detection only. Server expects a
    // client heartbeat every 5s; with the STOMP 1.5x grace this means a disconnect
    // is detected within ~7.5s, leaving headroom inside the 10s default
    // listener_grace_seconds before forceOffline fires.
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
