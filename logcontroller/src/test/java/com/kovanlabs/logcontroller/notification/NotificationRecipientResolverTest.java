package com.kovanlabs.logcontroller.notification;

import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.RolePermissionMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.notification.service.NotificationRecipientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationRecipientResolverTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private UserRoleMappingRepository userRoleMappingRepository;
    @Mock private RolePermissionMappingRepository rolePermissionMappingRepository;
    @Mock private UserServiceMappingRepository userServiceMappingRepository;

    @InjectMocks private NotificationRecipientResolver resolver;

    // ── resolveRecipients — blank service ─────────────────────────────────────

    @Test
    void resolveRecipients_blankServiceName_returnsEmptyList() {
        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("   ");
        assertThat(result).isEmpty();
        verify(appUserRepository, never()).findByIsActiveTrueOrderByUsernameAsc();
    }

    @Test
    void resolveRecipients_nullServiceName_returnsEmptyList() {
        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients(null);
        assertThat(result).isEmpty();
    }

    // ── resolveRecipients — no active users ───────────────────────────────────

    @Test
    void resolveRecipients_noActiveUsers_returnsEmptyList() {
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of());

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).isEmpty();
    }

    // ── resolveRecipients — user without alerts:read permission ──────────────

    @Test
    void resolveRecipients_userWithoutAlertsReadPermission_excluded() {
        AppUser user = user("KL00001", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(user));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenReturn(List.of(PermissionName.LOGS_READ)); // no alerts:read

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).isEmpty();
    }

    // ── resolveRecipients — admin user ────────────────────────────────────────

    @Test
    void resolveRecipients_adminUserWithAlertsRead_includedForAnyService() {
        AppUser admin = user("KL00001", "admin@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(admin));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("any-service");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("admin@test.com");
        assertThat(result.get(0).admin()).isTrue();
    }

    // ── resolveRecipients — dev user with matching service ───────────────────

    @Test
    void resolveRecipients_devUserMappedToService_included() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("payment-service"));

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("dev@test.com");
        assertThat(result.get(0).admin()).isFalse();
    }

    // ── resolveRecipients — dev user without matching service ────────────────

    @Test
    void resolveRecipients_devUserNotMappedToService_excluded() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("auth-service")); // different service

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).isEmpty();
    }

    // ── resolveRecipients — deduplication ────────────────────────────────────

    @Test
    void resolveRecipients_duplicateEmailsDeduped() {
        AppUser user1 = user("KL00001", "admin@test.com");
        AppUser user2 = user("KL00002", "ADMIN@TEST.COM"); // same email, different case
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc())
                .thenReturn(List.of(user1, user2));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId(anyString()))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId(anyString()))
                .thenReturn(List.of("ADMIN"));

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).hasSize(1);
    }

    // ── resolveRecipients — user with null email ──────────────────────────────

    @Test
    void resolveRecipients_userWithNullEmail_skipped() {
        AppUser noEmail = new AppUser();
        noEmail.setId("KL00003");
        noEmail.setEmail(null);
        noEmail.setActive(true);

        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(noEmail));

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        assertThat(result).isEmpty();
    }

    // ── resolveRecipients — DB error per user does not abort resolution ───────

    @Test
    void resolveRecipients_dbErrorForOneUser_continuesWithOtherUsers() {
        AppUser user1 = user("KL00001", "dev1@test.com");
        AppUser user2 = user("KL00002", "dev2@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc())
                .thenReturn(List.of(user1, user2));

        // user1 throws, user2 succeeds
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenThrow(new RuntimeException("DB error"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("ADMIN"));

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service");

        // user2 should still be resolved
        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("dev2@test.com");
    }

    // ── resolveRecipients — service name case-insensitive ────────────────────

    @Test
    void resolveRecipients_serviceNameCaseInsensitiveMatch_devIncluded() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.ALERTS_READ));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("Payment-Service")); // mixed case in DB

        List<NotificationRecipientResolver.RecipientInfo> result =
                resolver.resolveRecipients("payment-service"); // lowercase in alert

        assertThat(result).hasSize(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser user(String id, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        u.setUsername(email.split("@")[0]);
        u.setActive(true);
        return u;
    }
}
