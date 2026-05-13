package com.kovanlabs.logcontroller.config;

import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;


@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketSecurityConfig.class);

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthorizationInterceptor());
    }


    private static class StompAuthorizationInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            SimpMessageHeaderAccessor accessor =
                    SimpMessageHeaderAccessor.wrap(message);

            SimpMessageType messageType = accessor.getMessageType();
            Principal principal = accessor.getUser();
            String destination = accessor.getDestination();

            // CONNECT — require an authenticated principal.
            // The HTTP handshake interceptor already rejects unauthenticated upgrades,
            // but we enforce it here as a second layer of defence.
            if (SimpMessageType.CONNECT.equals(messageType)) {
                if (principal == null || principal.getName() == null
                        || principal.getName().isBlank()) {
                    LOGGER.warn("WS SECURITY — CONNECT rejected: no authenticated principal");
                    throw new org.springframework.security.access.AccessDeniedException(
                            "WebSocket CONNECT requires authentication");
                }
                LOGGER.debug("WS SECURITY — CONNECT allowed for principal='{}'",
                        principal.getName());
                return message;
            }

            // SUBSCRIBE — require authentication and block shared /topic subscriptions.
            if (SimpMessageType.SUBSCRIBE.equals(messageType)) {
                if (principal == null || principal.getName() == null
                        || principal.getName().isBlank()) {
                    LOGGER.warn("WS SECURITY — SUBSCRIBE rejected: no authenticated principal "
                            + "for destination='{}'", destination);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "WebSocket SUBSCRIBE requires authentication");
                }

                // Block subscriptions to shared /topic destinations.
                // All log delivery is user-specific (/user/queue/logs); shared topics
                // are never used and must not be subscribable.
                if (destination != null && destination.startsWith("/topic/")) {
                    LOGGER.warn("WS SECURITY — SUBSCRIBE to /topic denied for principal='{}' "
                            + "destination='{}'", principal.getName(), destination);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Subscriptions to /topic destinations are not permitted");
                }

                LOGGER.debug("WS SECURITY — SUBSCRIBE allowed for principal='{}' destination='{}'",
                        principal.getName(), destination);
                return message;
            }

            // All other frame types (SEND, DISCONNECT, HEARTBEAT, ACK, etc.)
            // require an authenticated principal.
            if (messageType != null
                    && messageType != SimpMessageType.HEARTBEAT
                    && messageType != SimpMessageType.OTHER) {
                if (principal == null || principal.getName() == null
                        || principal.getName().isBlank()) {
                    LOGGER.warn("WS SECURITY — {} frame rejected: no authenticated principal",
                            messageType);
                    throw new org.springframework.security.access.AccessDeniedException(
                            "WebSocket message requires authentication");
                }
            }

            return message;
        }
    }
}
