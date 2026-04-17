package com.kovanlabs.logcontroller.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthRequestContext {

    private static final String REQUEST_ATTR = AuthRequestContext.class.getName() + ".context";

    private AuthRequestContext() {
    }

    public static AuthenticatedUserContext getRequired(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTR);
        if (existing instanceof AuthenticatedUserContext context) {
            return context;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication required");
        }

        AuthenticatedUserContext context = fromAuthentication(authentication);
        request.setAttribute(REQUEST_ATTR, context);
        return context;
    }

    private static AuthenticatedUserContext fromAuthentication(Authentication authentication) {
        String email = resolveEmail(authentication.getPrincipal(), authentication.getName());

        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("admin"));

        UserRole role = isAdmin ? UserRole.ADMIN : UserRole.USER;

        List<String> allowedServices = extractAllowedServices(authentication.getPrincipal());
        return new AuthenticatedUserContext(email, role, allowedServices, List.of());
    }

    private static String resolveEmail(Object principal, String fallback) {
        if (principal instanceof OidcUser oidcUser) {
            return firstNonBlank(
                    oidcUser.getEmail(),
                    oidcUser.getPreferredUsername(),
                    oidcUser.getAttribute("upn"),
                    oidcUser.getAttribute("email"),
                    fallback);
        }

        if (principal instanceof OAuth2User oauth2User) {
            return firstNonBlank(
                    oauth2User.getAttribute("email"),
                    oauth2User.getAttribute("preferred_username"),
                    oauth2User.getAttribute("upn"),
                    fallback);
        }

        return fallback;
    }

    private static List<String> extractAllowedServices(Object principal) {
        Object raw = null;
        if (principal instanceof OidcUser oidcUser) {
            raw = oidcUser.getAttribute("allowed_services");
        } else if (principal instanceof OAuth2User oauth2User) {
            raw = oauth2User.getAttribute("allowed_services");
        }

        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object value : collection) {
                if (value != null) {
                    String text = value.toString().trim();
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                }
            }
            return values;
        }

        return List.of();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown@local";
    }
}
