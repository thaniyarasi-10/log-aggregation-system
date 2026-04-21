package com.kovanlabs.logcontroller.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.ServiceAccessRequest;
import com.kovanlabs.logcontroller.model.UserServiceMapping;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.AppUserRepository;
import com.kovanlabs.logcontroller.repository.ServiceAccessRequestRepository;
import com.kovanlabs.logcontroller.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.service.OAuthUserEmailResolver;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@RequestMapping("/api/services")
public class ServiceAccessController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceAccessController.class);

    private final ServiceAccessAuthorizationService authorizationService;
    private final OAuthUserEmailResolver emailResolver;
    private final AppServiceRepository appServiceRepository;
    private final AppUserRepository appUserRepository;
    private final ServiceAccessRequestRepository serviceAccessRequestRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;

    public ServiceAccessController(
            ServiceAccessAuthorizationService authorizationService,
            OAuthUserEmailResolver emailResolver,
            AppServiceRepository appServiceRepository,
            AppUserRepository appUserRepository,
            ServiceAccessRequestRepository serviceAccessRequestRepository,
            UserServiceMappingRepository userServiceMappingRepository) {
        this.authorizationService = authorizationService;
        this.emailResolver = emailResolver;
        this.appServiceRepository = appServiceRepository;
        this.appUserRepository = appUserRepository;
        this.serviceAccessRequestRepository = serviceAccessRequestRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
    }

    @GetMapping
    public ResponseEntity<List<String>> getAccessibleServices(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.ok(List.of());
            }

                List<AppService> accessibleServices = appServiceRepository.findByIsActiveTrue();

                List<String> names = accessibleServices.stream()
                    .filter(Objects::nonNull)
                    .map(AppService::getName)
                    .filter(Objects::nonNull)
                    .map(name -> name.toLowerCase(Locale.ROOT).trim())
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();

            return ResponseEntity.ok(names);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to resolve DB-backed services. Returning empty list: {}", ex.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/details")
    public ResponseEntity<List<ServiceSummaryView>> getAccessibleServiceDetails(Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.ok(List.of());
            }

            String email = resolveCurrentEmail(authentication);
            List<AppService> accessibleServices = authorizationService.getAccessibleServices(email);

            List<ServiceSummaryView> response = accessibleServices.stream()
                    .filter(Objects::nonNull)
                    .filter(service -> service.getName() != null && !service.getName().isBlank())
                    .map(service -> new ServiceSummaryView(
                            service.getId() == null ? null : service.getId().toString(),
                            service.getName().trim(),
                            service.getDescription() == null ? "" : service.getDescription().trim(),
                            service.isActive()
                    ))
                    .toList();

            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to resolve DB-backed service details. Returning empty list: {}", ex.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/requests/mine")
    public ResponseEntity<List<ServiceRequestView>> myRequests(Authentication authentication) {
        AppUser requester = resolveCurrentUser(authentication);
        List<ServiceRequestView> requests = serviceAccessRequestRepository.findByRequestedByOrderByCreatedAtDesc(requester.getId())
                .stream()
            .map(this::toView)
                .toList();
        return ResponseEntity.ok(requests);
    }

    @GetMapping("/requests")
    public ResponseEntity<List<ServiceRequestView>> requests(Authentication authentication) {
        AppUser requester = resolveCurrentUser(authentication);
        AuthenticatedUserContext accessContext = authorizationService.getUserAccessContext(requester.getEmail());
        boolean canManageServices = authorizationService.canManageServices(accessContext);

        List<ServiceAccessRequest> entities = canManageServices
                ? serviceAccessRequestRepository.findAllByOrderByCreatedAtDesc()
            : serviceAccessRequestRepository.findByRequestedByOrderByCreatedAtDesc(requester.getId());

        List<ServiceRequestView> requests = entities.stream()
            .map(this::toView)
                .toList();

        return ResponseEntity.ok(requests);
    }

    @PostMapping("/request")
    public ResponseEntity<ServiceRequestView> requestServiceAlias(
            Authentication authentication,
            @RequestBody ServiceRequestCreateRequest request) {
        return requestService(authentication, request);
    }

    @PostMapping("/requests")
    @Transactional
    public ResponseEntity<ServiceRequestView> requestService(
            Authentication authentication,
            @RequestBody ServiceRequestCreateRequest request) {
        AppUser requester = resolveCurrentUser(authentication);

        if (request == null || request.serviceName() == null || request.serviceName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service name is required");
        }

        ServiceAccessRequest entity = new ServiceAccessRequest();
        entity.setRequestedBy(requester.getId());
        entity.setServiceName(request.serviceName().toLowerCase(Locale.ROOT).trim());
        entity.setDescription(request.description() == null ? "" : request.description().trim());
        entity.setStatus(ServiceAccessRequest.RequestStatus.PENDING);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        ServiceAccessRequest saved = serviceAccessRequestRepository.save(entity);
        LOGGER.info(
                "Inserted service request id={} service='{}' requestedBy={} status={}",
                saved.getId(),
                saved.getServiceName(),
                saved.getRequestedBy(),
                saved.getStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    @PostMapping("/{requestId}/approve")
    @Transactional
    public ResponseEntity<ServiceSummaryView> approveRequest(
            Authentication authentication,
            @PathVariable("requestId") UUID requestId,
            @RequestBody(required = false) ServiceRequestDecisionRequest request) {
        AppUser reviewer = resolveCurrentUser(authentication);
        AuthenticatedUserContext accessContext = authorizationService.getUserAccessContext(reviewer.getEmail());
        if (!authorizationService.canManageServices(accessContext)) {
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
        serviceAccessRequestRepository.save(serviceRequest);

        return ResponseEntity.ok(new ServiceSummaryView(
                savedService.getId() == null ? null : savedService.getId().toString(),
                savedService.getName(),
                savedService.getDescription() == null ? "" : savedService.getDescription(),
                savedService.isActive()));
    }

    @PostMapping("/{requestId}/reject")
    @Transactional
    public ResponseEntity<ServiceRequestView> rejectRequest(
            Authentication authentication,
            @PathVariable("requestId") UUID requestId,
            @RequestBody(required = false) ServiceRequestDecisionRequest request) {
        AppUser reviewer = resolveCurrentUser(authentication);
        AuthenticatedUserContext accessContext = authorizationService.getUserAccessContext(reviewer.getEmail());
        if (!authorizationService.canManageServices(accessContext)) {
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
        return ResponseEntity.ok(toView(saved));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<ServiceRequestView> requestById(
            Authentication authentication,
            @PathVariable("requestId") UUID requestId) {
        AppUser requester = resolveCurrentUser(authentication);
        ServiceAccessRequest request = serviceAccessRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));

        if (!request.getRequestedBy().equalsIgnoreCase(requester.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }

        return ResponseEntity.ok(toView(request));
    }

    private String resolveCurrentEmail(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String email = emailResolver.getCurrentUserEmail().orElse(authentication.getName());
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to resolve user identity");
        }
        return email.trim().toLowerCase();
    }

    private AppUser resolveCurrentUser(Authentication authentication) {
        String email = resolveCurrentEmail(authentication);
        return appUserRepository.findByEmailIgnoreCase(email)
                .map(this::ensureActiveUser)
                .orElseGet(() -> provisionUserFromEmail(email));
    }

    private AppUser ensureActiveUser(AppUser user) {
        if (user.isActive()) {
            return user;
        }

        user.setActive(true);
        user.setUpdatedAt(LocalDateTime.now());
        return appUserRepository.save(user);
    }

    private AppUser provisionUserFromEmail(String email) {
        AppUser user = new AppUser();
        user.setId("u-" + UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(email);
        user.setUsername(buildProvisionedUsername(email));
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return appUserRepository.save(user);
    }

    private String buildProvisionedUsername(String email) {
        String localPart = email.split("@", 2)[0]
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-");
        if (localPart.isBlank()) {
            localPart = "user";
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String username = localPart + "." + suffix;
        if (username.length() > 50) {
            username = username.substring(0, 50);
        }
        return username;
    }

    private ServiceRequestView toView(ServiceAccessRequest request) {
        String requesterId = request.getRequestedBy();
        String requesterEmail = requesterId == null ? null : appUserRepository.findById(requesterId)
                .map(AppUser::getEmail)
            .orElse(requesterId);

        return new ServiceRequestView(
                request.getId(),
                requesterId,
                requesterEmail,
                request.getServiceName(),
                request.getDescription(),
                request.getStatus().name(),
                null,
                request.getCreatedAt(),
                null);
    }

    public record ServiceRequestCreateRequest(
            String serviceName,
            String description) {
    }

        public record ServiceRequestDecisionRequest(
            String comment,
            String description) {
        }

        public record ServiceSummaryView(
            String id,
            String name,
            String description,
            boolean active) {
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
}
