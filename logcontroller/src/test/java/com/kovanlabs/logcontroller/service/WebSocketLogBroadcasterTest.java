package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketLogBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AppUserRepository appUserRepository;
    @Mock private UserRoleMappingRepository userRoleMappingRepository;
    @Mock private UserServiceMappingRepository userServiceMappingRepository;

    private WebSocketLogBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new WebSocketLogBroadcaster(
                messagingTemplate, appUserRepository,
                userRoleMappingRepository, userServiceMappingRepository);
    }

    // ── broadcast — null / blank guards ──────────────────────────────────────

    @Test
    void broadcast_nullEvent_doesNotSendAnyMessage() {
        broadcaster.broadcast(null);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void broadcast_eventWithNullService_doesNotSendAnyMessage() {
        LogEvent event = logEvent(null, "ERROR");
        broadcaster.broadcast(event);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void broadcast_eventWithBlankService_doesNotSendAnyMessage() {
        LogEvent event = logEvent("   ", "ERROR");
        broadcaster.broadcast(event);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── broadcast — admin receives all services ───────────────────────────────

    @Test
    void broadcast_adminUser_receivesEventForAnyService() {
        AppUser admin = user("KL00001", "admin@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(admin));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));

        LogEvent event = logEvent("payment-service", "ERROR");
        broadcaster.broadcast(event);

        verify(messagingTemplate).convertAndSendToUser(
                eq("admin@test.com"), eq("/queue/logs"), eq(event));
    }

    @Test
    void broadcast_adminUser_receivesEventForUnmappedService() {
        AppUser admin = user("KL00001", "admin@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(admin));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));

        LogEvent event = logEvent("some-other-service", "INFO");
        broadcaster.broadcast(event);

        verify(messagingTemplate).convertAndSendToUser(
                eq("admin@test.com"), eq("/queue/logs"), eq(event));
    }

    // ── broadcast — dev receives only mapped services ─────────────────────────

    @Test
    void broadcast_devUser_receivesEventForMappedService() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("payment-service"));

        LogEvent event = logEvent("payment-service", "ERROR");
        broadcaster.broadcast(event);

        verify(messagingTemplate).convertAndSendToUser(
                eq("dev@test.com"), eq("/queue/logs"), eq(event));
    }

    @Test
    void broadcast_devUser_doesNotReceiveEventForUnmappedService() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("auth-service"));

        LogEvent event = logEvent("payment-service", "ERROR");
        broadcaster.broadcast(event);

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void broadcast_devUserWithNoServices_doesNotReceiveAnyEvent() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of());

        broadcaster.broadcast(logEvent("payment-service", "ERROR"));

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── broadcast — user with no roles is excluded ────────────────────────────

    @Test
    void broadcast_userWithNoRoles_isExcludedFromRouting() {
        AppUser noRoleUser = user("KL00003", "norole@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(noRoleUser));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00003"))
                .thenReturn(List.of());

        broadcaster.broadcast(logEvent("payment-service", "ERROR"));

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── broadcast — service name case-insensitive matching ───────────────────

    @Test
    void broadcast_serviceNameCaseInsensitiveMatch_devReceivesEvent() {
        AppUser dev = user("KL00002", "dev@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(dev));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("Payment-Service")); // mixed case in DB

        LogEvent event = logEvent("payment-service", "ERROR"); // lowercase in event
        broadcaster.broadcast(event);

        verify(messagingTemplate).convertAndSendToUser(
                eq("dev@test.com"), eq("/queue/logs"), eq(event));
    }

    // ── broadcast — messaging template throws ────────────────────────────────

    @Test
    void broadcast_messagingTemplateThrows_doesNotPropagateException() {
        AppUser admin = user("KL00001", "admin@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(admin));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));
        doThrow(new RuntimeException("WebSocket broker down"))
                .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

        // Must not throw
        broadcaster.broadcast(logEvent("payment-service", "ERROR"));
    }

    // ── broadcast — multiple users ────────────────────────────────────────────

    @Test
    void broadcast_multipleUsers_sendsToEligibleUsersOnly() {
        AppUser admin = user("KL00001", "admin@test.com");
        AppUser dev   = user("KL00002", "dev@test.com");
        AppUser other = user("KL00003", "other@test.com");

        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc())
                .thenReturn(List.of(admin, dev, other));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("DEV"));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00003"))
                .thenReturn(List.of("DEV"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00002"))
                .thenReturn(List.of("payment-service"));
        when(userServiceMappingRepository.findServiceNamesByUserId("KL00003"))
                .thenReturn(List.of("auth-service")); // different service

        LogEvent event = logEvent("payment-service", "ERROR");
        broadcaster.broadcast(event);

        // Admin and dev with payment-service should receive; other dev should not
        verify(messagingTemplate).convertAndSendToUser(eq("admin@test.com"), any(), any());
        verify(messagingTemplate).convertAndSendToUser(eq("dev@test.com"), any(), any());
        verify(messagingTemplate, never()).convertAndSendToUser(eq("other@test.com"), any(), any());
    }

    // ── invalidateCacheForUser ────────────────────────────────────────────────

    @Test
    void invalidateCacheForUser_blankEmail_doesNothing() {
        broadcaster.invalidateCacheForUser("   ");
        verify(appUserRepository, never()).findByEmailIgnoreCaseAndIsActiveTrue(any());
    }

    @Test
    void invalidateCacheForUser_nullEmail_doesNothing() {
        broadcaster.invalidateCacheForUser(null);
        verify(appUserRepository, never()).findByEmailIgnoreCaseAndIsActiveTrue(any());
    }

    @Test
    void invalidateCacheForUser_knownUser_rebuildsProfileInCache() {
        AppUser user = user("KL00001", "admin@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("admin@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));

        broadcaster.invalidateCacheForUser("admin@test.com");

        verify(appUserRepository).findByEmailIgnoreCaseAndIsActiveTrue("admin@test.com");
    }

    @Test
    void invalidateCacheForUser_deactivatedUser_removedFromRouting() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("gone@test.com"))
                .thenReturn(Optional.empty());

        broadcaster.invalidateCacheForUser("gone@test.com");

        // No exception; user simply removed from routing
        verify(appUserRepository).findByEmailIgnoreCaseAndIsActiveTrue("gone@test.com");
    }

    // ── invalidateCache ───────────────────────────────────────────────────────

    @Test
    void invalidateCache_expiresCacheAndRebuildOnNextBroadcast() {
        AppUser admin = user("KL00001", "admin@test.com");
        when(appUserRepository.findByIsActiveTrueOrderByUsernameAsc()).thenReturn(List.of(admin));
        when(userRoleMappingRepository.findDistinctRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("ADMIN"));

        broadcaster.broadcast(logEvent("svc", "INFO")); // builds cache
        broadcaster.invalidateCache();
        broadcaster.broadcast(logEvent("svc", "INFO")); // should rebuild

        // findByIsActiveTrueOrderByUsernameAsc called twice (once per cache build)
        verify(appUserRepository, times(2)).findByIsActiveTrueOrderByUsernameAsc();
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

    private LogEvent logEvent(String service, String level) {
        LogEvent e = new LogEvent();
        e.setService(service);
        e.setLevel(level);
        e.setMessage("test message");
        e.setTimestamp("2026-05-01T10:00:00Z");
        return e;
    }
}
