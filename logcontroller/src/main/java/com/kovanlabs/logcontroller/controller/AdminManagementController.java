package com.kovanlabs.logcontroller.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.kovanlabs.logcontroller.model.ServiceAccessRequest;
import com.kovanlabs.logcontroller.model.UserRoleMapping;
import com.kovanlabs.logcontroller.model.UserServiceMapping;
import com.kovanlabs.logcontroller.jpa.repository.AppRoleRepository;
import com.kovanlabs.logcontroller.jpa.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.ServiceAccessRequestRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@RequestMapping("/api/admin")
public class AdminManagementController {

    private static final String USER_ID_PATTERN = "^KL\\d{5}$";

    private final ServiceAccessAuthorizationService authorizationService;
    private final AppUserRepository appUserRepository;
    private final AppRoleRepository appRoleRepository;
    private final AppServiceRepository appServiceRepository;
    private final ServiceAccessRequestRepository serviceAccessRequestRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;

    public AdminManagementController(
            ServiceAccessAuthorizationService authorizationService,
            AppUserRepository appUserRepository,
            AppRoleRepository appRoleRepository,
            AppServiceRepository appServiceRepository,
            ServiceAccessRequestRepository serviceAccessRequestRepository,
            UserRoleMappingRepository userRoleMappingRepository,
            UserServiceMappingRepository userServiceMappingRepository) {
        this.authorizationService = authorizationService;
        this.appUserRepository = appUserRepository;
        this.appRoleRepository = appRoleRepository;
        this.appServiceRepository = appServiceRepository;
        this.serviceAccessRequestRepository = serviceAccessRequestRepository;
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
        try {
            appUserRepository.save(user);
            syncUserRoles(user, request == null ? null : request.roles());
            syncUserServices(user, request == null ? null : request.services());
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user data", ex);
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database temporarily unavailable", ex);
        }

        return ResponseEntity.ok(toAdminUserView(user));
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<AdminUserView> createUser(@RequestBody CreateUserRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageUsers(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        if (request.id() == null || request.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID is required");
        }

        String userId = request.id().trim().toUpperCase(Locale.ROOT);
        if (userId.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID must be at most 50 characters");
        }

        if (!userId.matches(USER_ID_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID must match format KL10004");
        }

        if (appUserRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User ID already exists");
        }

        String username = normalizeUsername(request.username());
        String email = normalizeEmail(request.email());

        if (appUserRepository.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already in use");
        }
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        AppUser user = new AppUser();
        user.setId(userId);
        user.setUsername(username);
        user.setEmail(email);
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        try {
            AppUser savedUser = appUserRepository.save(user);
            syncUserRoles(savedUser, resolveRoleNames(request));
            syncUserServices(savedUser, resolveServiceNames(request));
            return ResponseEntity.status(HttpStatus.CREATED).body(toAdminUserView(savedUser));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user data", ex);
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database temporarily unavailable", ex);
        }
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
            .filter(Objects::nonNull)
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

        String normalizedName = request.name().toLowerCase(Locale.ROOT).trim();
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

    @PostMapping("/services/{serviceId}")
    @Transactional
    public ResponseEntity<AdminServiceView> updateService(
            @PathVariable("serviceId") UUID serviceId,
            @RequestBody CreateServiceRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        AppService service = appServiceRepository.findById(serviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));

        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service name is required");
        }

        String normalizedName = request.name().toLowerCase(Locale.ROOT).trim();
        String description = request.description() == null ? "" : request.description().trim();

        AppService existing = appServiceRepository.findByNameIgnoreCase(normalizedName).orElse(null);
        if (existing != null && !existing.getId().equals(serviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Service name already in use");
        }

        service.setName(normalizedName);
        service.setDescription(description);
        service.setUpdatedAt(LocalDateTime.now());

        AppService saved = appServiceRepository.save(service);
        return ResponseEntity.ok(new AdminServiceView(
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

    @GetMapping("/services/requests")
    public ResponseEntity<List<ServiceRequestView>> serviceRequests() {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        List<ServiceRequestView> requests = serviceAccessRequestRepository
            .findAllByOrderByCreatedAtDesc()
                .stream()
                .map(AdminManagementController::toServiceRequestView)
                .toList();

        return ResponseEntity.ok(requests);
    }

    @PostMapping("/services/requests/{requestId}/approve")
    @Transactional
    public ResponseEntity<ServiceRequestView> approveServiceRequest(
            @PathVariable("requestId") UUID requestId,
            @RequestBody(required = false) ServiceRequestDecisionRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        ServiceAccessRequest serviceRequest = serviceAccessRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (serviceRequest.getStatus() != ServiceAccessRequest.RequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already processed");
        }

        String normalizedServiceName = serviceRequest.getServiceName().toLowerCase(Locale.ROOT).trim();
        String description = request != null && request.description() != null
                ? request.description().trim()
                : (serviceRequest.getDescription() == null ? "" : serviceRequest.getDescription().trim());

        AppService service = appServiceRepository.findByNameIgnoreCase(normalizedServiceName)
                .orElseGet(() -> {
                    AppService created = new AppService();
                    created.setName(normalizedServiceName);
                    created.setDescription(description);
                    created.setActive(true);
                    created.setCreatedAt(LocalDateTime.now());
                    created.setUpdatedAt(LocalDateTime.now());
                    return created;
                });

        service.setName(normalizedServiceName);
        service.setDescription(description);
        service.setActive(true);
        service.setUpdatedAt(LocalDateTime.now());
        AppService savedService = appServiceRepository.save(service);

        String requesterId = serviceRequest.getRequestedBy();
        List<String> currentServices = userServiceMappingRepository.findServiceNamesByUserId(requesterId);
        if (currentServices.stream().noneMatch(name -> name.equalsIgnoreCase(savedService.getName()))) {
            appUserRepository.findById(requesterId).ifPresent(requester -> {
                UserServiceMapping mapping = new UserServiceMapping();
                mapping.setUser(requester);
                mapping.setService(savedService);
                mapping.setCreatedAt(LocalDateTime.now());
                mapping.setUpdatedAt(LocalDateTime.now());
                userServiceMappingRepository.save(mapping);
            });
        }

        serviceRequest.setStatus(ServiceAccessRequest.RequestStatus.APPROVED);
        serviceRequest.setUpdatedAt(LocalDateTime.now());

        ServiceAccessRequest savedRequest = serviceAccessRequestRepository.save(serviceRequest);
        return ResponseEntity.ok(toServiceRequestView(savedRequest));
    }

    @PostMapping("/services/requests/{requestId}/reject")
    @Transactional
    public ResponseEntity<ServiceRequestView> rejectServiceRequest(
            @PathVariable("requestId") UUID requestId,
            @RequestBody(required = false) ServiceRequestDecisionRequest request) {
        AuthenticatedUserContext context = authorizationService.getCurrentUserAccessContext();
        if (!authorizationService.canManageServices(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }

        ServiceAccessRequest serviceRequest = serviceAccessRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (serviceRequest.getStatus() != ServiceAccessRequest.RequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already processed");
        }

        serviceRequest.setStatus(ServiceAccessRequest.RequestStatus.REJECTED);
        serviceRequest.setUpdatedAt(LocalDateTime.now());

        ServiceAccessRequest saved = serviceAccessRequestRepository.save(serviceRequest);
        return ResponseEntity.ok(toServiceRequestView(saved));
    }

    private AdminUserView toAdminUserView(AppUser user) {
        List<String> roles = userRoleMappingRepository.findDistinctRoleNamesByUserId(user.getId()).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
            .map(this::normalizeRoleName)
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
                .map(this::normalizeRoleName)
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

    private static ServiceRequestView toServiceRequestView(ServiceAccessRequest request) {
        String requestedByUserId = request.getRequestedBy();
        return new ServiceRequestView(
                request.getId(),
                requestedByUserId,
                requestedByUserId,
                request.getServiceName(),
                request.getDescription(),
                request.getStatus().name(),
            null,
                request.getCreatedAt(),
            null);
    }

    private List<String> resolveRoleNames(CreateUserRequest request) {
        List<String> names = new ArrayList<>();
        if (request.roles() != null) {
            names.addAll(request.roles());
        }
        if (request.role() != null && !request.role().isBlank()) {
            names.add(request.role());
        }
        return names;
    }

    private List<String> resolveServiceNames(CreateUserRequest request) {
        List<String> names = new ArrayList<>();
        if (request.services() != null) {
            names.addAll(request.services());
        }
        if (request.serviceName() != null && !request.serviceName().isBlank()) {
            names.add(request.serviceName());
        }
        return names;
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        return username.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRoleName(String roleName) {
        if (roleName == null) {
            return "";
        }
        return roleName.trim().toUpperCase(Locale.ROOT);
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

        Set<String> activeServiceNames = appServiceRepository.findByIsActiveTrue().stream()
                .map(AppService::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        List<String> existingServiceNames = normalizedServiceNames.stream()
                .filter(name -> activeServiceNames.contains(name.toLowerCase(Locale.ROOT)))
                .toList();

        if (existingServiceNames.size() != normalizedServiceNames.size()) {
            List<String> missing = normalizedServiceNames.stream()
                .filter(name -> !activeServiceNames.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown or inactive service(s): " + String.join(", ", missing));
        }

        if (existingServiceNames.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<UserServiceMapping> mappings = existingServiceNames.stream()
                .map(serviceName -> {
                    AppService service = appServiceRepository.findByNameIgnoreCase(serviceName)
                            .orElse(null);

                    if (service == null || !service.isActive()) {
                        return null;
                    }

                    UserServiceMapping mapping = new UserServiceMapping();
                    mapping.setUser(user);
                    mapping.setService(service);
                    mapping.setCreatedAt(now);
                    mapping.setUpdatedAt(now);
                    return mapping;
                })
                .filter(Objects::nonNull)
                .toList();

        if (mappings.isEmpty()) {
            return;
        }

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

        public record ServiceRequestView(
            UUID id,
            String requestedByUserId,
            String requestedByEmail,
            String serviceName,
            String description,
            String status,
            String reviewComment,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt) {
        }

        public record ServiceRequestDecisionRequest(
            String comment,
            String description) {
        }

        public record CreateUserRequest(
            String id,
            String username,
            String email,
            String role,
            String serviceName,
            List<String> services,
            List<String> roles) {
        }

    public record UpdateUserRequest(
            String username,
            String email,
            List<String> services,
            List<String> roles) {
    }
}
