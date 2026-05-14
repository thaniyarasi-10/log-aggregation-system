package com.kovanlabs.logcontroller.notification;

import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.NotificationPreferenceRepository;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import com.kovanlabs.logcontroller.notification.service.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private AppUserRepository appUserRepository;

    @InjectMocks private NotificationPreferenceService service;

    private AppUser testUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setId("KL00001");
        testUser.setEmail("dev@test.com");
        testUser.setUsername("dev");
        testUser.setActive(true);
    }

    // ── getOrCreatePreference ─────────────────────────────────────────────────

    @Test
    void getOrCreatePreference_existingPreference_returnsIt() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(testUser));
        NotificationPreference existing = preference(testUser, true);
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.of(existing));

        NotificationPreference result = service.getOrCreatePreference("dev@test.com");

        assertThat(result.isEmailEnabled()).isTrue();
        verify(preferenceRepository, never()).saveAndFlush(any());
    }

    @Test
    void getOrCreatePreference_noExistingPreference_createsDefaultWithEmailEnabled() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(testUser));
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.empty());

        NotificationPreference created = preference(testUser, true);
        when(preferenceRepository.saveAndFlush(any())).thenReturn(created);

        NotificationPreference result = service.getOrCreatePreference("dev@test.com");

        assertThat(result.isEmailEnabled()).isTrue();
        verify(preferenceRepository).saveAndFlush(any());
    }

    @Test
    void getOrCreatePreference_userNotFound_throws404() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("unknown@test.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreatePreference("unknown@test.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    void getOrCreatePreference_createdPreferenceHasCorrectUser() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(testUser));
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.empty());

        ArgumentCaptor<NotificationPreference> captor =
                ArgumentCaptor.forClass(NotificationPreference.class);
        when(preferenceRepository.saveAndFlush(captor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        service.getOrCreatePreference("dev@test.com");

        NotificationPreference saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(testUser);
        assertThat(saved.isEmailEnabled()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    // ── getPreferenceOrDefault ────────────────────────────────────────────────

    @Test
    void getPreferenceOrDefault_existingPreference_returnsIt() {
        NotificationPreference existing = preference(testUser, false);
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.of(existing));

        NotificationPreference result = service.getPreferenceOrDefault("KL00001");

        assertThat(result.isEmailEnabled()).isFalse();
    }

    @Test
    void getPreferenceOrDefault_noPreference_returnsTransientDefaultWithEmailEnabled() {
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.empty());

        NotificationPreference result = service.getPreferenceOrDefault("KL00001");

        assertThat(result.isEmailEnabled()).isTrue();
        // Transient — must NOT be persisted
        verify(preferenceRepository, never()).save(any());
        verify(preferenceRepository, never()).saveAndFlush(any());
    }

    // ── updatePreference ──────────────────────────────────────────────────────

    @Test
    void updatePreference_enableEmail_savesAndReturnsUpdatedPreference() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(testUser));
        NotificationPreference existing = preference(testUser, false);
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationPreference result = service.updatePreference("dev@test.com", true);

        assertThat(result.isEmailEnabled()).isTrue();
        assertThat(result.getUpdatedAt()).isNotNull();
        verify(preferenceRepository).save(existing);
    }

    @Test
    void updatePreference_disableEmail_savesAndReturnsUpdatedPreference() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("dev@test.com"))
                .thenReturn(Optional.of(testUser));
        NotificationPreference existing = preference(testUser, true);
        when(preferenceRepository.findByUser_Id("KL00001")).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationPreference result = service.updatePreference("dev@test.com", false);

        assertThat(result.isEmailEnabled()).isFalse();
    }

    @Test
    void updatePreference_userNotFound_throws404() {
        when(appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue("unknown@test.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePreference("unknown@test.com", true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private NotificationPreference preference(AppUser user, boolean emailEnabled) {
        NotificationPreference pref = new NotificationPreference();
        pref.setUser(user);
        pref.setEmailEnabled(emailEnabled);
        return pref;
    }
}
