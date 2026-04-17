package com.kovanlabs.logcontroller.auth;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record AuthenticatedUserContext(
        String email,
        UserRole role,
        List<String> allowedServices,
        List<String> permissions) {

    public AuthenticatedUserContext {
        email = email == null ? "unknown@local" : email;
        role = role == null ? UserRole.USER : role;
        allowedServices = allowedServices == null ? List.of() : List.copyOf(allowedServices);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public AuthenticatedUserContext(String email, UserRole role, List<String> allowedServices) {
        this(email, role, allowedServices, List.of());
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isServiceAllowed(String service) {
        if (isAdmin()) {
            return true;
        }
        if (service == null || service.isBlank()) {
            return false;
        }
        String normalized = service.trim().toLowerCase(Locale.ROOT);
        return allowedServices.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals(normalized));
    }

    public boolean hasPermission(String permissionName) {
        if (permissionName == null || permissionName.isBlank()) {
            return false;
        }
        String normalized = permissionName.trim().toLowerCase(Locale.ROOT);
        return permissions.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals(normalized));
    }
}
