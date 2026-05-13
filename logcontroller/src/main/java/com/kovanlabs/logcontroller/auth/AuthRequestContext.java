package com.kovanlabs.logcontroller.auth;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-scoped helper that retrieves the RBAC context for the current HTTP request.
 *
 * <p><strong>Security note:</strong> This class no longer builds an
 * {@link AuthenticatedUserContext} from OAuth token claims. All authorization
 * decisions must go through
 * {@link com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService},
 * which is the only place that performs DB-backed RBAC resolution.
 *
 * <p>This class is kept for backward compatibility with any code that calls
 * {@link #getRequired(HttpServletRequest)}, but callers should prefer injecting
 * {@code ServiceAccessAuthorizationService} directly.
 */
public final class AuthRequestContext {

    private static final String REQUEST_ATTR = AuthRequestContext.class.getName() + ".context";

    private AuthRequestContext() {
    }

    /**
     * Returns the RBAC context previously stored on the request by a controller
     * that already called {@code ServiceAccessAuthorizationService.getCurrentUserAccessContext()}.
     *
     * <p>If no context has been stored yet, this method verifies that the request
     * is authenticated and throws 401 if not. It does <em>not</em> perform a DB
     * lookup — callers must use {@code ServiceAccessAuthorizationService} for that.
     *
     * @throws ResponseStatusException 401 if the request is not authenticated
     */
    public static AuthenticatedUserContext getRequired(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTR);
        if (existing instanceof AuthenticatedUserContext context) {
            return context;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication required");
        }

        // No DB-backed context is available on this request yet.
        // Callers must use ServiceAccessAuthorizationService.getCurrentUserAccessContext()
        // to obtain a fully resolved RBAC context before calling this method.
        throw new ResponseStatusException(UNAUTHORIZED,
                "RBAC context not initialized for this request. " +
                "Use ServiceAccessAuthorizationService.getCurrentUserAccessContext() first.");
    }

    /**
     * Stores a resolved RBAC context on the request so it can be retrieved cheaply
     * by subsequent calls to {@link #getRequired(HttpServletRequest)} within the
     * same request lifecycle.
     */
    public static void store(HttpServletRequest request, AuthenticatedUserContext context) {
        if (request != null && context != null) {
            request.setAttribute(REQUEST_ATTR, context);
        }
    }
}
