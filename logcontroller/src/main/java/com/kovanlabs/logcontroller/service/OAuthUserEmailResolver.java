package com.kovanlabs.logcontroller.service;

import java.util.Optional;
import java.util.Collection;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class OAuthUserEmailResolver {

    public Optional<String> getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        return resolveEmail(authentication.getPrincipal(), authentication.getName());
    }

    private Optional<String> resolveEmail(Object principal, String fallback) {
        if (principal instanceof OidcUser oidcUser) {
            String emailsClaim = firstCollectionValue(oidcUser.getAttribute("emails"));
            return firstNonBlank(
                    oidcUser.getPreferredUsername(),
                    oidcUser.getEmail(),
                    oidcUser.getAttribute("email"),
                    oidcUser.getAttribute("upn"),
                oidcUser.getAttribute("unique_name"),
                emailsClaim,
                    fallback);
        }

        if (principal instanceof OAuth2User oauth2User) {
            String emailsClaim = firstCollectionValue(oauth2User.getAttribute("emails"));
            return firstNonBlank(
                    oauth2User.getAttribute("preferred_username"),
                    oauth2User.getAttribute("email"),
                    oauth2User.getAttribute("upn"),
                oauth2User.getAttribute("unique_name"),
                emailsClaim,
                    fallback);
        }

        return firstNonBlank(fallback);
    }

    private Optional<String> firstNonBlank(String... values) {
        if (values == null) {
            return Optional.empty();
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }

        return Optional.empty();
    }

    private String firstCollectionValue(Object raw) {
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                if (value != null) {
                    String text = value.toString().trim();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }
}
