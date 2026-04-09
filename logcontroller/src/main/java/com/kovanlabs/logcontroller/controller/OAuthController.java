package com.kovanlabs.logcontroller.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.auth.AuthRequestContext;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.AuthorizationService;
import com.kovanlabs.logcontroller.auth.OAuthTokenVerifierService;
import com.kovanlabs.logcontroller.auth.VerifiedOAuthUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true", allowedHeaders = "*")
public class OAuthController {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String frontendUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OAuthTokenVerifierService tokenVerifierService;
    private final AuthorizationService authorizationService;

    public OAuthController(
            @Value("${spring.security.oauth2.client.registration.google.client-id:${GOOGLE_CLIENT_ID:}}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:${GOOGLE_CLIENT_SECRET:}}") String clientSecret,
            @Value("${app.oauth.redirect-uri:http://localhost:8080/login/oauth2/code/google}") String redirectUri,
            @Value("${app.oauth.frontend-url:http://localhost:3000}") String frontendUrl,
            OAuthTokenVerifierService tokenVerifierService,
            AuthorizationService authorizationService
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.frontendUrl = frontendUrl;
        this.tokenVerifierService = tokenVerifierService;
        this.authorizationService = authorizationService;
    }

    private void validateGoogleOAuthConfig() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Google OAuth is not configured. Set spring.security.oauth2.client.registration.google.client-id and client-secret.");
        }
    }

    // 1. Redirect to Google OAuth
    @GetMapping("/oauth2/authorization/google")
    public void authorize(HttpServletResponse response) throws IOException {
        validateGoogleOAuthConfig();
        String authUrl = UriComponentsBuilder
            .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "email profile")
                .queryParam("access_type", "online")
                .encode()
                .build()
                .toUriString();
        response.sendRedirect(authUrl);
    }

    // 2. Handle Google Callback
    @GetMapping("/login/oauth2/code/google")
    public void callback(@RequestParam("code") String code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            validateGoogleOAuthConfig();
            // Exchange code for Access Token
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("code", code);
            params.add("redirect_uri", redirectUri);
            params.add("grant_type", "authorization_code");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            ResponseEntity<String> tokenResponse = restTemplate.postForEntity(
                    "https://oauth2.googleapis.com/token", entity, String.class);

            JsonNode tokenNode = mapper.readTree(tokenResponse.getBody());
            String idToken = tokenNode.has("id_token") ? tokenNode.get("id_token").asText() : null;
            if (idToken == null || idToken.isBlank()) {
                throw new IllegalStateException("Google OAuth response did not include id_token");
            }

            VerifiedOAuthUser verifiedUser = tokenVerifierService.verifyIdToken(idToken);
            authorizationService.resolveContextByVerifiedEmail(verifiedUser.email());
            
            response.sendRedirect(frontendUrl + "?token=" + java.net.URLEncoder.encode(idToken, "UTF-8"));

        } catch (Exception e) {
            e.printStackTrace();
            response.sendRedirect(frontendUrl + "?error=" + java.net.URLEncoder.encode(e.getMessage(), "UTF-8"));
        }
    }

    // 3. User Identity Validation Endpoint
    @GetMapping("/api/auth/me")
    public ResponseEntity<?> getAuthUser(HttpServletRequest request) {
        AuthenticatedUserContext context = AuthRequestContext.getRequired(request);
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", context.email());
        payload.put("role", context.role().name().toLowerCase());
        payload.put("services", context.isAdmin() ? List.of("*") : context.allowedServices());
        return ResponseEntity.ok(payload);
    }

    // 4. Terminate Auth
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok("{\"status\": \"logged_out\"}");
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logoutGet(HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok("{\"status\": \"logged_out\"}");
    }
}
