package com.kovanlabs.logcontroller.notification.service;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core notification service — resolves recipients, applies RBAC + preference filters,
 * and dispatches HTML emails asynchronously.
 *
 * <h3>Cooldown</h3>
 * <p>An in-memory map tracks the last send time per (recipientEmail, alertKey) pair.
 * No database is involved. The map is bounded by the number of active (recipient, alert)
 * combinations and is safe for concurrent access via {@link ConcurrentHashMap}.
 *
 * <h3>Failure isolation</h3>
 * <ul>
 *   <li>Email failures never propagate to the caller.</li>
 *   <li>Each recipient is processed independently.</li>
 *   <li>Retry with exponential backoff is applied per recipient per send attempt.</li>
 * </ul>
 */
@Service
public class AlertNotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertNotificationService.class);

    private final JavaMailSender mailSender;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationPreferenceService preferenceService;
    private final AlertEmailTemplateBuilder templateBuilder;

    private final String fromAddress;
    private final String fromName;
    private final int maxRetries;
    private final long retryBaseDelayMs;
    private final long cooldownMs;

    // In-memory cooldown: key = "recipientEmail|service|severity" → last sent epoch ms
    private final ConcurrentHashMap<String, Long> lastSentAt = new ConcurrentHashMap<>();

    public AlertNotificationService(
            JavaMailSender mailSender,
            NotificationRecipientResolver recipientResolver,
            NotificationPreferenceService preferenceService,
            AlertEmailTemplateBuilder templateBuilder,
            @Value("${notification.email.from-address:noreply@logcontroller.io}") String fromAddress,
            @Value("${notification.email.from-name:LogController Alerts}") String fromName,
            @Value("${notification.email.max-retries:3}") int maxRetries,
            @Value("${notification.email.retry-base-delay-ms:1000}") long retryBaseDelayMs,
            @Value("${notification.email.cooldown-minutes:5}") int cooldownMinutes) {
        this.mailSender       = mailSender;
        this.recipientResolver = recipientResolver;
        this.preferenceService = preferenceService;
        this.templateBuilder  = templateBuilder;
        this.fromAddress      = fromAddress;
        this.fromName         = fromName;
        this.maxRetries       = Math.max(1, maxRetries);
        this.retryBaseDelayMs = Math.max(100, retryBaseDelayMs);
        this.cooldownMs       = (long) Math.max(1, cooldownMinutes) * 60_000L;
    }

    /**
     * Entry point called by the async event listener.
     * Runs on the {@code notificationExecutor} thread pool — never blocks the scheduler.
     */
    public void processAlertNotification(AlertGeneratedEvent event) {
        Alert alert = event.getAlert();
        boolean severityTransition = event.isSeverityTransition();

        LOGGER.info("Processing notification — service='{}' severity='{}' transition={}",
                alert.getService(), alert.getSeverity(), severityTransition);

        List<NotificationRecipientResolver.RecipientInfo> recipients;
        try {
            recipients = recipientResolver.resolveRecipients(alert.getService());
        } catch (Exception ex) {
            LOGGER.error("Failed to resolve recipients for service='{}': {}",
                    alert.getService(), ex.getMessage(), ex);
            return;
        }

        if (recipients.isEmpty()) {
            LOGGER.debug("No eligible recipients for service='{}' — no emails sent", alert.getService());
            return;
        }

        LOGGER.info("Dispatching notifications — service='{}' severity='{}' recipients={}",
                alert.getService(), alert.getSeverity(), recipients.size());

        for (NotificationRecipientResolver.RecipientInfo recipient : recipients) {
            try {
                processRecipient(recipient, alert, severityTransition);
            } catch (Exception ex) {
                LOGGER.error("Unexpected error processing recipient='{}': {}",
                        recipient.email(), ex.getMessage(), ex);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — per-recipient processing
    // -------------------------------------------------------------------------

    private void processRecipient(NotificationRecipientResolver.RecipientInfo recipient,
                                  Alert alert, boolean severityTransition) {
        String email = recipient.email();

        // Check email preference — the only user-configurable gate
        NotificationPreference pref = safeGetPreference(recipient.userId());
        if (!pref.isEmailEnabled()) {
            LOGGER.debug("Skipped (email disabled) — recipient='{}'", email);
            return;
        }

        // In-memory cooldown check — bypass on severity escalation
        String cooldownKey = email + "|" + alert.getService() + "|" + alert.getSeverity();
        if (!severityTransition && isCoolingDown(cooldownKey)) {
            LOGGER.debug("Skipped (cooldown) — recipient='{}' service='{}'", email, alert.getService());
            return;
        }

        sendWithRetry(recipient, alert, cooldownKey);
    }

    private boolean isCoolingDown(String key) {
        Long last = lastSentAt.get(key);
        return last != null && (System.currentTimeMillis() - last) < cooldownMs;
    }

    private void sendWithRetry(NotificationRecipientResolver.RecipientInfo recipient,
                               Alert alert, String cooldownKey) {
        String email = recipient.email();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sendEmail(recipient, alert);
                lastSentAt.put(cooldownKey, System.currentTimeMillis());
                LOGGER.info("Email sent — recipient='{}' service='{}' severity='{}' attempt={}",
                        email, alert.getService(), alert.getSeverity(), attempt);
                return;
            } catch (MailException | MessagingException | java.io.UnsupportedEncodingException ex) {
                LOGGER.warn("Email send failed (attempt {}/{}) — recipient='{}': {}",
                        attempt, maxRetries, email, ex.getMessage());
                if (attempt < maxRetries) {
                    sleepExponentialBackoff(attempt);
                }
            }
        }

        LOGGER.error("Email delivery failed after {} attempts — recipient='{}' service='{}'",
                maxRetries, email, alert.getService());
    }

    private void sendEmail(NotificationRecipientResolver.RecipientInfo recipient, Alert alert)
            throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddress, fromName);
        helper.setTo(recipient.email());
        helper.setSubject(buildSubject(alert));
        helper.setText(
                templateBuilder.buildPlainTextEmail(alert, recipient.username()),
                templateBuilder.buildAlertEmail(alert, recipient.username())
        );
        mailSender.send(message);
    }

    private String buildSubject(Alert alert) {
        String severity = alert.getSeverity() != null ? alert.getSeverity().toUpperCase() : "ALERT";
        String service  = alert.getService()  != null ? alert.getService()  : "Unknown Service";
        return "[" + severity + "] Alert: " + service + " — " + alert.getCount() + " error(s) detected";
    }

    private NotificationPreference safeGetPreference(String userId) {
        try {
            return preferenceService.getPreferenceOrDefault(userId);
        } catch (Exception ex) {
            LOGGER.warn("Could not load notification preference for userId='{}' — using defaults: {}",
                    userId, ex.getMessage());
            NotificationPreference defaults = new NotificationPreference();
            defaults.setEmailEnabled(true);
            return defaults;
        }
    }

    private void sleepExponentialBackoff(int attempt) {
        long delay = retryBaseDelayMs * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
