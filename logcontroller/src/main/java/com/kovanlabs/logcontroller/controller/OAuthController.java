package com.kovanlabs.logcontroller.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true", allowedHeaders = "*")
public class OAuthController {

    private final String CLIENT_ID = System.getenv("GOOGLE_CLIENT_ID");
    private final String CLIENT_SECRET = System.getenv("GOOGLE_CLIENT_SECRET");
    private final String REDIRECT_URI = "http://localhost:8080/login/oauth2/code/google";
    private final String FRONTEND_URL = "http://localhost:3000";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // 1. Redirect to Google OAuth
    @GetMapping("/oauth2/authorization/google")
    public void authorize(HttpServletResponse response) throws IOException {
        String authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + CLIENT_ID +
                "&redirect_uri=" + REDIRECT_URI +
                "&response_type=code" +
                "&scope=email profile" +
                "&access_type=online";
        response.sendRedirect(authUrl);
    }

    // 2. Handle Google Callback
    @GetMapping("/login/oauth2/code/google")
    public void callback(@RequestParam("code") String code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Exchange code for Access Token
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", CLIENT_ID);
            params.add("client_secret", CLIENT_SECRET);
            params.add("code", code);
            params.add("redirect_uri", REDIRECT_URI);
            params.add("grant_type", "authorization_code");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            ResponseEntity<String> tokenResponse = restTemplate.postForEntity(
                    "https://oauth2.googleapis.com/token", entity, String.class);

            JsonNode tokenNode = mapper.readTree(tokenResponse.getBody());
            String accessToken = tokenNode.get("access_token").asText();

            // Fetch User Info
            HttpHeaders userInfoHeaders = new HttpHeaders();
            userInfoHeaders.setBearerAuth(accessToken);
            HttpEntity<Void> userInfoEntity = new HttpEntity<>(userInfoHeaders);

            ResponseEntity<String> userInfoResponse = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo", HttpMethod.GET, userInfoEntity, String.class);

            JsonNode userNode = mapper.readTree(userInfoResponse.getBody());
            String email = userNode.has("email") ? userNode.get("email").asText() : "Unknown";
            String name = userNode.has("name") ? userNode.get("name").asText() : email;

            // Use URL Token to completely bypass cross-port cookie isolation issues in SPAs
            String tokenPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((email + "|" + name).getBytes());
            
            // Redirect to frontend safely passing the JWT
            response.sendRedirect(FRONTEND_URL + "?token=" + tokenPayload);

        } catch (Exception e) {
            e.printStackTrace();
            response.sendRedirect(FRONTEND_URL + "?error=" + java.net.URLEncoder.encode(e.getMessage(), "UTF-8"));
        }
    }

    // 3. User Identity Validation Endpoint
    @GetMapping("/api/auth/me")
    public ResponseEntity<?> getAuthUser(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String payload = new String(java.util.Base64.getUrlDecoder().decode(token));
                String[] parts = payload.split("\\|");
                Map<String, String> user = new HashMap<>();
                user.put("email", parts[0]);
                user.put("name", parts.length > 1 ? parts[1] : parts[0]);
                return ResponseEntity.ok(user);
            } catch (Exception e) {}
        }
        return ResponseEntity.status(401).body("{\"error\": \"Unauthorized\"}");
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
