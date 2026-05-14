package com.kovanlabs.logcontroller.notification;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import com.kovanlabs.logcontroller.notification.service.AlertEmailTemplateBuilder;
import com.kovanlabs.logcontroller.notification.service.AlertNotificationService;
import com.kovanlabs.logcontroller.notification.service.NotificationPreferenceService;
import com.kovanlabs.logcontroller.notification.service.NotificationRecipientResolver;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertNotificationServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private AlertEmailTemplateBuilder templateBuilder;
    @Mock private MimeMessage mimeMessage;

    private AlertNotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new AlertNotificationService(
                mailSender, recipientResolver, preferenceService, templateBuilder,
                "noreply@test.com", "LogController Alerts",
                1,      // maxRetries = 1 (fast tests)
                10L,    // retryBaseDelayMs
                1       // cooldownMinutes = 1
        );
    }

    // ── processAlertNotification — no recipients ──────────────────────────────

    @Test
    void processAlertNotification_noRecipients_doesNotSendEmail() {
        when(recipientResolver.resolveRecipients(anyString())).thenReturn(List.of());

        notificationService.processAlertNotification(event(alert("payment-service", "CRITICAL"), false));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // ── processAlertNotification — email disabled ─────────────────────────────

    @Test
    void processAlertNotification_recipientEmailDisabled_skipsEmail() {
        NotificationRecipientResolver.RecipientInfo recipient =
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev@test.com", "Dev User", false);
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(List.of(recipient));

        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(false);
        when(preferenceService.getPreferenceOrDefault("KL00001")).thenReturn(pref);

        notificationService.processAlertNotification(event(alert("payment-service", "WARNING"), false));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // ── processAlertNotification — email enabled ──────────────────────────────

    @Test
    void processAlertNotification_emailEnabled_sendsEmail() throws Exception {
        NotificationRecipientResolver.RecipientInfo recipient =
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev@test.com", "Dev User", false);
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(List.of(recipient));

        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(true);
        when(preferenceService.getPreferenceOrDefault("KL00001")).thenReturn(pref);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateBuilder.buildAlertEmail(any(), any())).thenReturn("<html>alert</html>");
        when(templateBuilder.buildPlainTextEmail(any(), any())).thenReturn("plain text");

        notificationService.processAlertNotification(event(alert("payment-service", "CRITICAL"), false));

        verify(mailSender).send(mimeMessage);
    }

    // ── processAlertNotification — cooldown ───────────────────────────────────

    @Test
    void processAlertNotification_withinCooldown_secondCallSkipped() throws Exception {
        NotificationRecipientResolver.RecipientInfo recipient =
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev@test.com", "Dev User", false);
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(List.of(recipient));

        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(true);
        when(preferenceService.getPreferenceOrDefault("KL00001")).thenReturn(pref);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateBuilder.buildAlertEmail(any(), any())).thenReturn("<html>alert</html>");
        when(templateBuilder.buildPlainTextEmail(any(), any())).thenReturn("plain text");

        Alert a = alert("payment-service", "CRITICAL");
        notificationService.processAlertNotification(event(a, false)); // first — sends
        notificationService.processAlertNotification(event(a, false)); // second — cooldown

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void processAlertNotification_severityTransition_bypassesCooldown() throws Exception {
        NotificationRecipientResolver.RecipientInfo recipient =
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev@test.com", "Dev User", false);
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(List.of(recipient));

        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(true);
        when(preferenceService.getPreferenceOrDefault("KL00001")).thenReturn(pref);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateBuilder.buildAlertEmail(any(), any())).thenReturn("<html>alert</html>");
        when(templateBuilder.buildPlainTextEmail(any(), any())).thenReturn("plain text");

        Alert a = alert("payment-service", "CRITICAL");
        notificationService.processAlertNotification(event(a, false)); // first — sends
        notificationService.processAlertNotification(event(a, true));  // severity transition — bypasses cooldown

        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    // ── processAlertNotification — recipient resolver throws ─────────────────

    @Test
    void processAlertNotification_recipientResolverThrows_doesNotPropagateException() {
        when(recipientResolver.resolveRecipients(anyString()))
                .thenThrow(new RuntimeException("DB down"));

        // Must not throw
        notificationService.processAlertNotification(event(alert("payment-service", "CRITICAL"), false));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // ── processAlertNotification — preference service throws ─────────────────

    @Test
    void processAlertNotification_preferenceServiceThrows_usesDefaultEnabledPreference() throws Exception {
        NotificationRecipientResolver.RecipientInfo recipient =
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev@test.com", "Dev User", false);
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(List.of(recipient));
        when(preferenceService.getPreferenceOrDefault("KL00001"))
                .thenThrow(new RuntimeException("DB down"));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateBuilder.buildAlertEmail(any(), any())).thenReturn("<html>alert</html>");
        when(templateBuilder.buildPlainTextEmail(any(), any())).thenReturn("plain text");

        // Default preference is emailEnabled=true, so email should still be sent
        notificationService.processAlertNotification(event(alert("payment-service", "CRITICAL"), false));

        verify(mailSender).send(mimeMessage);
    }

    // ── processAlertNotification — multiple recipients ────────────────────────

    @Test
    void processAlertNotification_multipleRecipients_sendsToEach() throws Exception {
        List<NotificationRecipientResolver.RecipientInfo> recipients = List.of(
                new NotificationRecipientResolver.RecipientInfo("KL00001", "dev1@test.com", "Dev1", false),
                new NotificationRecipientResolver.RecipientInfo("KL00002", "dev2@test.com", "Dev2", false)
        );
        when(recipientResolver.resolveRecipients("payment-service")).thenReturn(recipients);

        NotificationPreference pref = new NotificationPreference();
        pref.setEmailEnabled(true);
        when(preferenceService.getPreferenceOrDefault(anyString())).thenReturn(pref);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateBuilder.buildAlertEmail(any(), any())).thenReturn("<html>alert</html>");
        when(templateBuilder.buildPlainTextEmail(any(), any())).thenReturn("plain text");

        notificationService.processAlertNotification(event(alert("payment-service", "CRITICAL"), false));

        verify(mailSender, times(2)).send(mimeMessage);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Alert alert(String service, String severity) {
        return new Alert(service, service + " has errors", 5, severity, Instant.now());
    }

    private AlertGeneratedEvent event(Alert alert, boolean severityTransition) {
        return new AlertGeneratedEvent(this, alert, severityTransition);
    }
}
