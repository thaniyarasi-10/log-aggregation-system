package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.jpa.repository.*;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceAccessAuthorizationServiceTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private AppServiceRepository appServiceRepository;
    @Mock private UserServiceMappingRepository userServiceMappingRepository;
    @Mock private UserRoleMappingRepository userRoleMappingRepository;
    @Mock private RolePermissionMappingRepository rolePermissionMappingRepository;
    @Mock private OAuthUserEmailResolver emailResolver;

    private ServiceAccessAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new ServiceAccessAuthorizationService(
                appUserRepository,
                appServiceRepository,
                userServiceMappingRepository,
                userRoleMappingRepository,
                rolePermissionMappingRepository,
                emailResolver,
                "",       // no admin email override
                5000L,    // db retry cooldown
                0L        // context cache TTL = 0 → always re-resolve (no caching in tests)
        );
    }

    // ── getUserAccessContext ──────────────────────────────────────────────────

    @Test
    void getUserAccessContext_knownDevUser_returnsDevContext() {
        AppUser user = user("KL00001", "dev@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("DEV"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenReturn(List.of(PermissionName.LOGS_READ));
        when(userServiceMappingRepository.findServicesByUserId("KL00001"))
                .thenReturn(List.of(appService("payment-service")));

        AuthenticatedUserContext ctx = service.getUserAccessContext("dev@test.com");

        assertThat(ctx.role()).isEqualTo(UserRole.DEV);
        assertThat(ctx.isAdmin()).isFalse();
        assertThat(ctx.allowedServices()).containsExactly("payment-service");
        assertThat(ctx.hasPermission(PermissionName.LOGS_READ)).isTrue();
    }

    @Test
    void getUserAccessContext_knownAdminUser_returnsAdminContext() {
        AppUser user = user("KL00002", "admin@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("admin@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("ADMIN"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.LOGS_READ, PermissionName.USERS_MANAGE));
        when(appServiceRepository.findByIsActiveTrue())
                .thenReturn(List.of(appService("payment-service"), appService("auth-service")));

        AuthenticatedUserContext ctx = service.getUserAccessContext("admin@test.com");

        assertThat(ctx.isAdmin()).isTrue();
        assertThat(ctx.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void getUserAccessContext_unknownUser_throws403() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserAccessContext("unknown@test.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void getUserAccessContext_userWithNoRoles_throws403() {
        AppUser user = user("KL00003", "noroles@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("noroles@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00003"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getUserAccessContext("noroles@test.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void getUserAccessContext_blankEmail_throws403() {
        assertThatThrownBy(() -> service.getUserAccessContext("   "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void getUserAccessContext_nullEmail_throws403() {
        assertThatThrownBy(() -> service.getUserAccessContext(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(403));
    }

    @Test
    void getUserAccessContext_dbThrows_throws503() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue(anyString()))
                .thenThrow(new RuntimeException("DB connection refused"));

        assertThatThrownBy(() -> service.getUserAccessContext("dev@test.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));
    }

    @Test
    void getUserAccessContext_emailNormalisedToLowercase() {
        AppUser user = user("KL00001", "dev@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("DEV"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenReturn(List.of(PermissionName.LOGS_READ));
        when(userServiceMappingRepository.findServicesByUserId("KL00001"))
                .thenReturn(List.of());

        // Pass uppercase — should be normalised before DB lookup
        AuthenticatedUserContext ctx = service.getUserAccessContext("DEV@TEST.COM");

        assertThat(ctx.email()).isEqualTo("dev@test.com");
    }

    // ── canManageUsers / canManageServices ────────────────────────────────────

    @Test
    void canManageUsers_adminContext_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN, List.of(), List.of());
        assertThat(service.canManageUsers(ctx)).isTrue();
    }

    @Test
    void canManageUsers_devWithUsersManagePermission_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.USERS_MANAGE));
        assertThat(service.canManageUsers(ctx)).isTrue();
    }

    @Test
    void canManageUsers_devWithoutPermission_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.LOGS_READ));
        assertThat(service.canManageUsers(ctx)).isFalse();
    }

    @Test
    void canManageUsers_nullContext_returnsFalse() {
        assertThat(service.canManageUsers(null)).isFalse();
    }

    @Test
    void canManageServices_adminContext_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN, List.of(), List.of());
        assertThat(service.canManageServices(ctx)).isTrue();
    }

    @Test
    void canManageServices_devWithServicesManagePermission_returnsTrue() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.SERVICES_MANAGE));
        assertThat(service.canManageServices(ctx)).isTrue();
    }

    @Test
    void canManageServices_devWithoutPermission_returnsFalse() {
        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of(PermissionName.LOGS_READ));
        assertThat(service.canManageServices(ctx)).isFalse();
    }

    // ── getAccessibleServices ─────────────────────────────────────────────────

    @Test
    void getAccessibleServices_adminUser_returnsAllActiveServices() {
        AppUser user = user("KL00002", "admin@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("admin@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00002"))
                .thenReturn(List.of("ADMIN"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00002"))
                .thenReturn(List.of(PermissionName.LOGS_READ));
        when(appServiceRepository.findByIsActiveTrue())
                .thenReturn(List.of(appService("payment-service"), appService("auth-service")));

        List<AppService> services = service.getAccessibleServices("admin@test.com");

        assertThat(services).hasSize(2);
    }

    @Test
    void getAccessibleServices_devUser_returnsOnlyMappedServices() {
        AppUser user = user("KL00001", "dev@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00001"))
                .thenReturn(List.of("DEV"));
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00001"))
                .thenReturn(List.of(PermissionName.LOGS_READ));
        when(userServiceMappingRepository.findServicesByUserId("KL00001"))
                .thenReturn(List.of(appService("payment-service")));

        List<AppService> services = service.getAccessibleServices("dev@test.com");

        assertThat(services).hasSize(1);
        assertThat(services.get(0).getName()).isEqualTo("payment-service");
    }

    @Test
    void getAccessibleServices_blankEmail_returnsEmptyList() {
        List<AppService> services = service.getAccessibleServices("   ");
        assertThat(services).isEmpty();
    }

    // ── admin email override ──────────────────────────────────────────────────

    @Test
    void getUserAccessContext_adminEmailOverride_elevatesContextToAdmin() {
        // Create service with admin email override configured
        ServiceAccessAuthorizationService svcWithOverride = new ServiceAccessAuthorizationService(
                appUserRepository, appServiceRepository, userServiceMappingRepository,
                userRoleMappingRepository, rolePermissionMappingRepository, emailResolver,
                "superadmin@test.com", 5000L, 0L);

        AppUser user = user("KL00099", "superadmin@test.com");
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("superadmin@test.com"))
                .thenReturn(Optional.of(user));
        when(userRoleMappingRepository.findRoleNamesByUserId("KL00099"))
                .thenReturn(List.of("DEV")); // DB says DEV
        when(rolePermissionMappingRepository.findPermissionNamesByUserId("KL00099"))
                .thenReturn(List.of(PermissionName.LOGS_READ));
        when(userServiceMappingRepository.findServicesByUserId("KL00099"))
                .thenReturn(List.of());
        when(appServiceRepository.findByIsActiveTrue())
                .thenReturn(List.of(appService("payment-service")));

        AuthenticatedUserContext ctx = svcWithOverride.getUserAccessContext("superadmin@test.com");

        // Override should elevate to ADMIN
        assertThat(ctx.isAdmin()).isTrue();
        assertThat(ctx.role()).isEqualTo(UserRole.ADMIN);
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

    private AppService appService(String name) {
        AppService s = new AppService();
        s.setName(name);
        s.setActive(true);
        return s;
    }
}
