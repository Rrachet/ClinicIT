package com.clinicit.realtime;

import com.clinicit.identity.security.CorsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * STOMP over WebSocket at {@code /ws}, with Spring's in-memory broker for {@code /topic}.
 * The in-memory broker is enough for a single instance; running several instances needs
 * a shared broker (see docs/REALTIME.md).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties cors;
    private final StompSecurityInterceptor security;
    private final RealtimeConnections connections;
    private TaskScheduler heartbeatScheduler;

    public WebSocketConfig(CorsProperties cors, StompSecurityInterceptor security, RealtimeConnections connections) {
        this.cors = cors;
        this.security = security;
        this.connections = connections;
    }

    @Autowired
    void setHeartbeatScheduler(@Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.heartbeatScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same origin list as the REST API's CORS. Empty means same-origin only.
        registry.addEndpoint("/ws").setAllowedOrigins(cors.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler);
        // No @MessageMapping handlers exist, and SEND frames are rejected by the interceptor.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(16 * 1024)       // clients only send tiny CONNECT/SUBSCRIBE frames
                .setSendTimeLimit(10_000)
                .setSendBufferSizeLimit(512 * 1024)   // slow consumers are dropped, not buffered forever
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        connections.opened(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                        connections.closed(session);
                        super.afterConnectionClosed(session, status);
                    }
                });
    }
}
