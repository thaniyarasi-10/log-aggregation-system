package com.kovanlabs.logcontroller.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.model.AppRole;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.UserRoleMapping;
import com.kovanlabs.logcontroller.model.UserServiceMapping;
import com.kovanlabs.logcontroller.repository.AppRoleRepository;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.AppUserRepository;
import com.kovanlabs.logcontroller.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@RequestMapping("/api/admin")
public class AdminManagementController {

    private final ServiceAccessAuthorizationService authorizationService;
    private final AppUserRepository appUserRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppServiceRepository appServiceRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;

    public AdminManagementController(
            ServiceAccessAuthorizationService authorizationService,
            AppUserRepository appUserRepository,
            AppRoleRepository appRoleRepository,
            AppServiceRepository appServiceRepository,
            UserRoleMappingRepository userRoleMappingRepository,
            UserServiceMappingRepository userServiceMappingRepository) {
        this.authorizationService = authorizationService;
        this.appUserRepository = appUserRepository;
        this.appRoleRepository = appRoleRepository;
        this.appServiceRepository = appServiceRepository;
        this.userRoleMappingRepository = userRoleMappingRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserView>> users() {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageUsers(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        List<AdminUserView> response = appUserRepository.findByIsActiveTrueOrderByUsernameAsc().stream()
                .map(this::toAdminUserView)
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/users/{userId}")
    @Transactional
    public ResponseEntity<AdminUserView> updateUser(
            @PathVariable("userId") String userId,
            @RequestBody UpdateUserRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageUsers(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String updatedUsername = request != null && request.username() != null
                ? request.username().trim()
                : user.getUsername();
        String updatedEmail = request != null && request.email() != null
                ? request.email().trim().toLowerCase(Locale.ROOT)
                : user.getEmail();

        if (updatedUsername == null || updatedUsername.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (updatedEmail == null || updatedEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }

        if (appUserRepository.existsByUsernameIgnoreCaseAndIdNot(updatedUsername, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }
        if (appUserRepository.existsByEmailIgnoreCaseAndIdNot(updatedEmail, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        user.setUsername(updatedUsername);
        user.setEmail(updatedEmail);
        user.setUpdatedAt(LocalDateTime.now());
        appUserRepository.save(user);

        syncUserRoles(user, request == null ? null : request.roles());
        syncUserServices(user, request == null ? null : request.services());

        return ResponseEntity.ok(toAdminUserView(user));
    }

    @DeleteMapping("/users/{userId}")
    @Transactional
    public ResponseEntity<Void> deactivateUser(@PathVariable("userId") String userId) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageUsers(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        appUserRepository.save(user);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/services")
    public ResponseEntity<List<AdminServiceView>> services() {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        List<AdminServiceView> response = appServiceRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(service -> new AdminServiceView(
                        service.getId(),
                        service.getName(),
                        service.getDescription(),
                        service.isActive()))
                .toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/services")
    @Transactional
    public ResponseEntity<AdminServiceView> createService(@RequestBody CreateServiceRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service name is required");
        }

        String normalizedName = request.name().trim();
        String description = request.description() == null ? "" : request.description().trim();

        AppService existing = appServiceRepository.findByNameIgnoreCase(normalizedName).orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Service already exists");
            }

            existing.setActive(true);
            existing.setDescription(description);
            existing.setUpdatedAt(LocalDateTime.now());
            AppService saved = appServiceRepository.save(existing);
            return ResponseEntity.ok(new AdminServiceView(
                    saved.getId(),
                    saved.getName(),
                    saved.getDescription(),
                    saved.isActive()));
        }

        AppService service = new AppService();
        service.setName(normalizedName);
        service.setDescription(description);
        service.setActive(true);
        service.setCreatedAt(LocalDateTime.now());
        service.setUpdatedAt(LocalDateTime.now());

        AppService saved = appServiceRepository.save(service);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AdminServiceView(
                saved.getId(),
                saved.getName(),
                saved.getDescription(),
                saved.isActive()));
    }

    @DeleteMapping("/services/{serviceId}")
    @Transactional
    public ResponseEntity<Void> deactivateService(@PathVariable("serviceId") UUID serviceId) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        AppService service = appServiceRepository.findById(serviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));

        service.setActive(false);
        service.setUpdatedAt(LocalDateTime.now());
        appServiceRepository.save(service);

        return ResponseEntity.noContent().build();
    }

    private AdminUserView toAdminUserView(AppUser user) {
        List<String> roles = userRoleMappingRepository.findDistinctRoleNamesByUserId(user.getId()).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        List<String> services = userServiceMappingRepository.findServiceNamesByUserId(user.getId()).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        return new AdminUserView(user.getId(), user.getUsername(), user.getEmail(), services, roles);
    }

    private void syncUserRoles(AppUser user, List<String> roleNames) {
        if (roleNames == null) {
            return;
        }

        List<String> normalizedRoleNames = roleNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        userRoleMappingRepository.deleteByUser_Id(user.getId());
        if (normalizedRoleNames.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<UserRoleMapping> mappings = normalizedRoleNames.stream()
                .map(roleName -> {
                    AppRole role = appRoleRepository.findByNameIgnoreCase(roleName)
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "Unknown role: " + roleName));

                    UserRoleMapping mapping = new UserRoleMapping();
                    mapping.setUser(user);
                    mapping.setRole(role);
                    mapping.setAssignedAt(now);
                    mapping.setUpdatedAt(now);
                    return mapping;
                })
                .toList();

        userRoleMappingRepository.saveAll(mappings);
    }

    private void syncUserServices(AppUser user, List<String> serviceNames) {
        if (serviceNames == null) {
            return;
        }

        List<String> normalizedServiceNames = serviceNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        userServiceMappingRepository.deleteByUser_Id(user.getId());
        if (normalizedServiceNames.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<UserServiceMapping> mappings = normalizedServiceNames.stream()
                .map(serviceName -> {
                    AppService service = appServiceRepository.findByNameIgnoreCase(serviceName)
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "Unknown service: " + serviceName));

                    UserServiceMapping mapping = new UserServiceMapping();
                    mapping.setUser(user);
                    mapping.setService(service);
                    mapping.setCreatedAt(now);
                    mapping.setUpdatedAt(now);
                    return mapping;
                })
                .toList();

        userServiceMappingRepository.saveAll(mappings);
    }

    public record AdminUserView(
            String id,
            String username,
            String email,
            List<String> services,
            List<String> roles) {
    }

    public record AdminServiceView(
            UUID id,
            String name,
            String description,
            boolean active) {
    }

    public record CreateServiceRequest(
            String name,
            String description) {
    }

    public record UpdateUserRequest(
            String username,
            String email,
            List<String> services,
            List<String> roles) {
    }
}
