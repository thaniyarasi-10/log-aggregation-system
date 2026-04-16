package com.kovanlabs.logcontroller.controller;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.service.OAuthUserEmailResolver;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@RequestMapping("/api/services")
public class ServiceAccessController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceAccessController.class);

    private final ServiceAccessAuthorizationService authorizationService;
    private final OAuthUserEmailResolver emailResolver;

    public ServiceAccessController(
            ServiceAccessAuthorizationService authorizationService,
            OAuthUserEmailResolver emailResolver) {
        this.authorizationService = authorizationService;
        this.emailResolver = emailResolver;
    }

    @GetMapping
    public ResponseEntity<List<String>> getAccessibleServices(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.ok(List.of());
            }

            String email = emailResolver.getCurrentUserEmail().orElse("");
            List<String> names = authorizationService.getAccessibleServices(email).stream()
                    .map(AppService::getName)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
            return ResponseEntity.ok(names);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to resolve DB-backed services. Returning empty list: {}", ex.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }
}
