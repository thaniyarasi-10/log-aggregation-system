package com.kovanlabs.logcontroller.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true", allowedHeaders = "*")
public class OAuthController {

    private final ServiceAccessAuthorizationService authorizationService;
    private final String backendBaseUrl;
    private final String oauthRedirectUri;

    public OAuthController(
            ServiceAccessAuthorizationService authorizationService,
            @org.springframework.beans.factory.annotation.Value("${app.oauth.backend-url:http://localhost:8080}") String backendBaseUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.client.registration.azure.redirect-uri:http://localhost:8080/login/oauth2/code/azure}") String oauthRedirectUri) {
        this.authorizationService = authorizationService;
        this.backendBaseUrl = backendBaseUrl;
        this.oauthRedirectUri = oauthRedirectUri;
    }

    @GetMapping("/api/auth/login")
    public void login(HttpServletResponse response) throws java.io.IOException {
        String normalizedBaseUrl = backendBaseUrl != null && !backendBaseUrl.isBlank()
                ? backendBaseUrl.replaceAll("/$", "")
                : "http://localhost:8080";
        response.sendRedirect(normalizedBaseUrl + "/oauth2/authorization/azure");
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> getAuthUser(Authentication authentication) {
        Map<String, Object> payload = new HashMap<>();
        if (authentication == null || !authentication.isAuthenticated()) {
            payload.put("authenticated", false);
            return ResponseEntity.status(401).body(payload);
        }

        payload.put("authenticated", true);
        payload.put("authorities", authentication.getAuthorities().stream().map(Object::toString).toList());

        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            payload.put("name", oidcUser.getFullName() != null ? oidcUser.getFullName() : oidcUser.getName());
            payload.put("email", firstNonBlank(
                    oidcUser.getEmail(),
                    oidcUser.getPreferredUsername(),
                    oidcUser.getAttribute("upn"),
                    oidcUser.getAttribute("email"),
                    oidcUser.getName()));
            payload.put("claims", oidcUser.getClaims());
        } else if (principal instanceof OAuth2User oauth2User) {
            payload.put("name", firstNonBlank(
                    oauth2User.getAttribute("name"),
                    oauth2User.getAttribute("preferred_username"),
                    oauth2User.getName()));
            payload.put("email", firstNonBlank(
                    oauth2User.getAttribute("email"),
                    oauth2User.getAttribute("preferred_username"),
                    oauth2User.getAttribute("upn"),
                    oauth2User.getName()));
            payload.put("attributes", oauth2User.getAttributes());
        } else {
            payload.put("name", authentication.getName());
            payload.put("email", authentication.getName());
        }

        String email = (String) payload.getOrDefault("email", authentication.getName());
        AuthenticatedUserContext accessContext = authorizationService.getUserAccessContext(email);
        payload.put("role", accessContext.role().name());
        payload.put("permissions", accessContext.permissions());
        payload.put("allowedServices", accessContext.allowedServices());
        payload.put("canManageUsers", authorizationService.canManageUsers(accessContext));
        payload.put("canManageServices", authorizationService.canManageServices(accessContext));

        return ResponseEntity.ok(payload);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        request.getSession().invalidate();
        return ResponseEntity.ok("{\"status\": \"logged_out\"}");
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logoutGet(HttpServletRequest request, HttpServletResponse response) {
        request.getSession().invalidate();
        return ResponseEntity.ok("{\"status\": \"logged_out\"}");
    }

    @GetMapping("/oauth2-login-error")
    public ResponseEntity<?> oauth2LoginError(HttpServletRequest request) {
        Map<String, Object> errorResponse = new HashMap<>();
        String errorParam = request.getParameter("error");
        errorResponse.put("error", errorParam != null ? errorParam : "Unknown OAuth2 error");
        errorResponse.put("message", "Azure OAuth2 authentication failed. If error is authorization_request_not_found, start login from backend origin so OAuth2 state session is preserved.");
        errorResponse.put("redirectUri", oauthRedirectUri);
        return ResponseEntity.status(401).body(errorResponse);
    }

    @GetMapping("/api/auth/diagnostics")
    public ResponseEntity<?> diagnostics(org.springframework.core.env.Environment env) {
        Map<String, Object> diagnostics = new HashMap<>();
        String configuredClientId = env.getProperty("spring.security.oauth2.client.registration.azure.client-id");
        String configuredSecret = env.getProperty("spring.security.oauth2.client.registration.azure.client-secret");
        diagnostics.put("clientId", maskSecret(configuredClientId));
        diagnostics.put("clientSecret", "***REDACTED***");
        diagnostics.put("clientSecretConfigured", configuredSecret != null && !configuredSecret.isBlank());
        diagnostics.put("issuerUri", env.getProperty("spring.security.oauth2.client.provider.azure.issuer-uri"));
        diagnostics.put("redirectUri", oauthRedirectUri);
        diagnostics.put("scopes", env.getProperty("spring.security.oauth2.client.registration.azure.scope"));
        diagnostics.put("clientAuthMethod", env.getProperty("spring.security.oauth2.client.registration.azure.client-authentication-method"));
        diagnostics.put("authGrantType", env.getProperty("spring.security.oauth2.client.registration.azure.authorization-grant-type"));
        diagnostics.put("message", "Verify these values match your Azure App Registration exactly. Redirect URI must be registered in Azure portal.");
        return ResponseEntity.ok(diagnostics);
    }

    private static String maskSecret(String value) {
        if (value == null || value.length() <= 4) return "***";
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
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
