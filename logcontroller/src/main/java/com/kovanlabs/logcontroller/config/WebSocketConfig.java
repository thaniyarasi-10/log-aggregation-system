package com.kovanlabs.logcontroller.config;

import java.security.Principal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import jakarta.servlet.http.HttpSession;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketConfig.class);

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic  — shared broadcast destinations (kept for any future pub/sub use)
        // /queue  — user-specific destinations used by convertAndSendToUser
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        // Prefix that Spring prepends when routing to a specific user session.
        // convertAndSendToUser("alice@example.com", "/queue/logs", payload) will
        // deliver to the destination /user/alice@example.com/queue/logs on the
        // session(s) whose Principal.getName() equals "alice@example.com".
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                // Intercept the WebSocket handshake to inject an email-based Principal.
                // Spring Security's OAuth2 authentication.getName() returns the Azure
                // subject ID (a UUID), not the email. convertAndSendToUser() routes by
                // Principal.getName(), so we must override it with the email here.
                .addInterceptors(new EmailPrincipalHandshakeInterceptor())
                // Custom handshake handler that promotes the email principal stored in
                // the handshake attributes to the WebSocket session's Principal.
                .setHandshakeHandler(new EmailPrincipalHandshakeHandler());
    }

    /**
     * Handshake handler that uses the email-based Principal injected by
     * {@link EmailPrincipalHandshakeInterceptor} as the WebSocket session's Principal.
     * If no email principal was stored (unauthenticated request), falls back to the
     * default behaviour.
     */
    private static class EmailPrincipalHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(
                ServerHttpRequest request,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {
            Principal emailPrincipal = (Principal) attributes.get("email-principal");
            if (emailPrincipal != null) {
                return emailPrincipal;
            }
            // Fallback: let Spring use the default principal (Azure subject UUID).
            // convertAndSendToUser will still work if the broadcaster also uses getName().
            return super.determineUser(request, wsHandler, attributes);
        }
    }

    /**
     * Handshake interceptor that replaces the default OAuth2 principal (Azure subject UUID)
     * with an email-based principal so that {@code convertAndSendToUser(email, ...)} routing works.
     *
     * <p>During the WebSocket upgrade request the HTTP session is still available, so we can
     * read the Spring Security {@link Authentication} and extract the email from the OIDC/OAuth2
     * claims — the same logic used in {@code OAuthController.getAuthUser()}.
     */
    private static class EmailPrincipalHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {

            if (!(request instanceof ServletServerHttpRequest servletRequest)) {
                return true;
            }

            HttpSession session = servletRequest.getServletRequest().getSession(false);
            if (session == null) {
                // No HTTP session → no authentication → reject the upgrade.
                // Returning false sends HTTP 401 and prevents the WebSocket from opening.
                LOGGER.warn("WS HANDSHAKE REJECTED — no HTTP session; unauthenticated upgrade attempt blocked");
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            // Spring Security stores the Authentication in the session under this key
            Object securityContext = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (securityContext == null) {
                LOGGER.warn("WS HANDSHAKE REJECTED — no security context in session; upgrade blocked");
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            Authentication authentication = null;
            if (securityContext instanceof org.springframework.security.core.context.SecurityContext sc) {
                authentication = sc.getAuthentication();
            }

            if (authentication == null || !authentication.isAuthenticated()) {
                LOGGER.warn("WS HANDSHAKE REJECTED — unauthenticated principal; upgrade blocked");
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            String email = resolveEmail(authentication);
            if (email == null || email.isBlank()) {
                // Cannot route WebSocket messages without a stable email identity.
                // Reject the upgrade rather than silently connecting with no principal.
                LOGGER.warn("WS HANDSHAKE REJECTED — could not resolve email from principal '{}'; upgrade blocked",
                        authentication.getName());
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            LOGGER.debug("WS HANDSHAKE — resolved principal email='{}' for WebSocket session", email);

            // Store the email-based principal in the handshake attributes.
            // Spring's DefaultHandshakeHandler picks this up and sets it as the
            // WebSocket session's Principal, which STOMP then uses for /user routing.
            final String resolvedEmail = email;
            attributes.put("email-principal", new Principal() {
                @Override
                public String getName() {
                    return resolvedEmail;
                }
            });

            return true;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {
            // Nothing to do after handshake
        }

        private String resolveEmail(Authentication authentication) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                return firstNonBlank(
                        oidcUser.getEmail(),
                        oidcUser.getPreferredUsername(),
                        oidcUser.getAttribute("upn"),
                        oidcUser.getAttribute("email"),
                        oidcUser.getName());
            }
            if (principal instanceof OAuth2User oauth2User) {
                return firstNonBlank(
                        oauth2User.getAttribute("email"),
                        oauth2User.getAttribute("preferred_username"),
                        oauth2User.getAttribute("upn"),
                        oauth2User.getName());
            }
            return authentication.getName();
        }

        private String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }
}
