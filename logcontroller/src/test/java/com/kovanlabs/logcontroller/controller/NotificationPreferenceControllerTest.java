package com.kovanlabs.logcontroller.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import com.kovanlabs.logcontroller.notification.service.NotificationPreferenceService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceControllerTest {

    @Mock private NotificationPreferenceService preferenceService;
    @Mock private ServiceAccessAuthorizationService accessAuthorizationService;

    @InjectMocks private NotificationPreferenceController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getPreferences_returnsCurrentUserPreference() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        when(preferenceService.getOrCreatePreference("user@test.com"))
                .thenReturn(preference(true));

        mockMvc.perform(get("/api/notifications/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getPreferences_emailDisabled_returnsCorrectFlag() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        when(preferenceService.getOrCreatePreference("user@test.com"))
                .thenReturn(preference(false));

        mockMvc.perform(get("/api/notifications/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false));
    }

    @Test
    void updatePreferences_enableEmail_updatesAndReturnsNewState() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        when(preferenceService.getOrCreatePreference("user@test.com"))
                .thenReturn(preference(false));
        when(preferenceService.updatePreference("user@test.com", true))
                .thenReturn(preference(true));

        mockMvc.perform(put("/api/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("emailEnabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true));

        verify(preferenceService).updatePreference("user@test.com", true);
    }

    @Test
    void updatePreferences_disableEmail_updatesAndReturnsNewState() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        when(preferenceService.getOrCreatePreference("user@test.com"))
                .thenReturn(preference(true));
        when(preferenceService.updatePreference("user@test.com", false))
                .thenReturn(preference(false));

        mockMvc.perform(put("/api/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("emailEnabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false));
    }

    @Test
    void updatePreferences_missingEmailEnabledKey_usesCurrentValue() throws Exception {
        NotificationPreference current = preference(true);
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        when(preferenceService.getOrCreatePreference("user@test.com")).thenReturn(current);
        when(preferenceService.updatePreference("user@test.com", true)).thenReturn(current);

        mockMvc.perform(put("/api/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true));

        // Should use the current value (true) when key is absent
        verify(preferenceService).updatePreference("user@test.com", true);
    }

    @Test
    void getPreferences_preferenceHasNullTimestamps_serialisesAsNull() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext())
                .thenReturn(userContext("user@test.com"));
        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(true);
        // createdAt and updatedAt are null
        when(preferenceService.getOrCreatePreference("user@test.com")).thenReturn(pref);

        mockMvc.perform(get("/api/notifications/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").value(nullValue()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUserContext userContext(String email) {
        return new AuthenticatedUserContext(email, UserRole.DEV, List.of(), List.of());
    }

    private NotificationPreference preference(boolean emailEnabled) {
        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(emailEnabled);
        pref.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        pref.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return pref;
    }
}
