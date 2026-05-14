package com.kovanlabs.logcontroller.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserContextTest {

    // ── isAdmin ───────────────────────────────────────────────────────────────

    @Test
    void isAdmin_adminRole_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN, List.of(), List.of());
        assertThat(ctx.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_devRole_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        assertThat(ctx.isAdmin()).isFalse();
    }

    @Test
    void isAdmin_nullRole_defaultsToDevAndReturnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", null, List.of(), List.of());
        assertThat(ctx.isAdmin()).isFalse();
        assertThat(ctx.role()).isEqualTo(UserRole.DEV);
    }

    // ── isServiceAllowed ──────────────────────────────────────────────────────

    @Test
    void isServiceAllowed_adminAlwaysReturnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN, List.of("payment-service"), List.of());
        assertThat(ctx.isServiceAllowed("any-service")).isTrue();
        assertThat(ctx.isServiceAllowed("unknown-service")).isTrue();
    }

    @Test
    void isServiceAllowed_devWithMatchingService_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"), List.of());
        assertThat(ctx.isServiceAllowed("payment-service")).isTrue();
    }

    @Test
    void isServiceAllowed_devWithCaseInsensitiveMatch_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("Payment-Service"), List.of());
        assertThat(ctx.isServiceAllowed("payment-service")).isTrue();
        assertThat(ctx.isServiceAllowed("PAYMENT-SERVICE")).isTrue();
    }

    @Test
    void isServiceAllowed_devWithNoMatchingService_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("auth-service"), List.of());
        assertThat(ctx.isServiceAllowed("payment-service")).isFalse();
    }

    @Test
    void isServiceAllowed_devWithEmptyAllowedServices_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        assertThat(ctx.isServiceAllowed("payment-service")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void isServiceAllowed_nullOrBlankService_returnsFalse(String service) {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"), List.of());
        assertThat(ctx.isServiceAllowed(service)).isFalse();
    }

    // ── hasPermission ─────────────────────────────────────────────────────────

    @Test
    void hasPermission_matchingPermission_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(),
                List.of(PermissionName.LOGS_READ, PermissionName.METRICS_READ));
        assertThat(ctx.hasPermission(PermissionName.LOGS_READ)).isTrue();
    }

    @Test
    void hasPermission_caseInsensitiveMatch_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of("LOGS:READ"));
        assertThat(ctx.hasPermission("logs:read")).isTrue();
    }

    @Test
    void hasPermission_missingPermission_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.LOGS_READ));
        assertThat(ctx.hasPermission(PermissionName.METRICS_READ)).isFalse();
    }

    @Test
    void hasPermission_emptyPermissions_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        assertThat(ctx.hasPermission(PermissionName.LOGS_READ)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void hasPermission_nullOrBlankPermissionName_returnsFalse(String permission) {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.LOGS_READ));
        assertThat(ctx.hasPermission(permission)).isFalse();
    }

    // ── defensive defaults ────────────────────────────────────────────────────

    @Test
    void constructor_nullEmail_defaultsToUnknownAtLocal() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                null, UserRole.DEV, List.of(), List.of());
        assertThat(ctx.email()).isEqualTo("unknown@local");
    }

    @Test
    void constructor_nullAllowedServices_defaultsToEmptyList() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, null, List.of());
        assertThat(ctx.allowedServices()).isEmpty();
    }

    @Test
    void constructor_nullPermissions_defaultsToEmptyList() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), null);
        assertThat(ctx.permissions()).isEmpty();
    }

    @Test
    void constructor_allowedServicesListIsImmutable() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"), List.of());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> ctx.allowedServices().add("new-service"));
    }

    @Test
    void constructor_permissionsListIsImmutable() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.LOGS_READ));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> ctx.permissions().add("new-permission"));
    }

    // ── three-arg constructor ─────────────────────────────────────────────────

    @Test
    void threeArgConstructor_permissionsDefaultToEmpty() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"));
        assertThat(ctx.permissions()).isEmpty();
        assertThat(ctx.hasPermission(PermissionName.LOGS_READ)).isFalse();
    }
}
