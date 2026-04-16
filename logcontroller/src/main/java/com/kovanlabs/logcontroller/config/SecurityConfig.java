package com.kovanlabs.logcontroller.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);
    private final String frontendRedirectUrl;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthTokenUri;

    public SecurityConfig(
            @Value("${app.oauth.frontend-url:http://localhost:3000}") String frontendRedirectUrl,
            @Value("${spring.security.oauth2.client.registration.azure.client-id:}") String oauthClientId,
            @Value("${spring.security.oauth2.client.registration.azure.client-secret:}") String oauthClientSecret,
            @Value("${spring.security.oauth2.client.provider.azure.token-uri:}") String oauthTokenUri) {
        this.frontendRedirectUrl = frontendRedirectUrl;
        this.oauthClientId = oauthClientId;
        this.oauthClientSecret = oauthClientSecret;
        this.oauthTokenUri = oauthTokenUri;

        validateAzureOAuthConfiguration();
    }

    private void validateAzureOAuthConfiguration() {
        if (oauthClientId == null || oauthClientId.isBlank()) {
            throw new IllegalStateException(
                    "Missing OAuth client-id at spring.security.oauth2.client.registration.azure.client-id.");
        }
        if (oauthTokenUri == null || oauthTokenUri.isBlank()) {
            throw new IllegalStateException(
                "Missing OAuth token-uri at spring.security.oauth2.client.provider.azure.token-uri.");
        }
        if (oauthClientSecret == null || oauthClientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Missing OAuth client-secret at spring.security.oauth2.client.registration.azure.client-secret. Azure token exchange requires client_secret. Set AZURE_CLIENT_SECRET env var or pass -Dazure.client-secret=<secret value>. Use the Secret VALUE from Azure App Registration (not Secret ID).");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/oauth2/**", "/login/**", "/api/auth/login", "/oauth2-login-error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(authenticationSuccessHandler())
                        .failureHandler((request, response, exception) -> {
                            LOGGER.error("OAuth2 authentication failed: {}", exception.getMessage(), exception);
                            if (exception.getCause() != null) {
                                LOGGER.error("Root cause: {}", exception.getCause().getMessage(), exception.getCause());
                            }
                            // Keep redirect error payload short to avoid oversized header / URL issues.
                            String encodedError = URLEncoder.encode("oauth2_login_failed", StandardCharsets.UTF_8);
                            response.sendRedirect("/oauth2-login-error?error=" + encodedError);
                        }))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/api/auth/me")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"));

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return (request, response, authentication) -> {
            String name = authentication.getName();
            String email = authentication.getName();

            Object principal = authentication.getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                name = firstNonBlank(oidcUser.getFullName(), oidcUser.getName(), authentication.getName());
                email = firstNonBlank(
                        oidcUser.getEmail(),
                        oidcUser.getPreferredUsername(),
                        oidcUser.getAttribute("upn"),
                        oidcUser.getAttribute("email"),
                        authentication.getName());
            } else if (principal instanceof OAuth2User oauth2User) {
                name = firstNonBlank(
                        oauth2User.getAttribute("name"),
                        oauth2User.getAttribute("preferred_username"),
                        authentication.getName());
                email = firstNonBlank(
                        oauth2User.getAttribute("email"),
                        oauth2User.getAttribute("preferred_username"),
                        oauth2User.getAttribute("upn"),
                        authentication.getName());
            }

            LOGGER.info("Azure OAuth login successful. name='{}', email='{}'", name, email);
            response.sendRedirect(frontendRedirectUrl);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:8080"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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
