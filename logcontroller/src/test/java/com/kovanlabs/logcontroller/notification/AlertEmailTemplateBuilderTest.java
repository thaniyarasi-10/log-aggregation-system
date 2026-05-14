package com.kovanlabs.logcontroller.notification;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.notification.service.AlertEmailTemplateBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertEmailTemplateBuilderTest {

    private AlertEmailTemplateBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AlertEmailTemplateBuilder();
    }

    // ── buildAlertEmail ───────────────────────────────────────────────────────

    @Test
    void buildAlertEmail_criticalAlert_containsServiceAndSeverity() {
        Alert alert = alert("payment-service", "CRITICAL", 10);
        String html = builder.buildAlertEmail(alert, "Dev User");

        assertThat(html).contains("payment-service");
        assertThat(html).contains("CRITICAL");
        assertThat(html).contains("Dev User");
    }

    @Test
    void buildAlertEmail_warningAlert_containsWarningBadge() {
        Alert alert = alert("auth-service", "WARNING", 3);
        String html = builder.buildAlertEmail(alert, "Admin");

        assertThat(html).contains("WARNING");
        assertThat(html).contains("auth-service");
    }

    @Test
    void buildAlertEmail_nullRecipientName_usesGenericGreeting() {
        Alert alert = alert("payment-service", "CRITICAL", 5);
        String html = builder.buildAlertEmail(alert, null);

        assertThat(html).contains("Hello,");
        assertThat(html).doesNotContain("Hi null");
    }

    @Test
    void buildAlertEmail_blankRecipientName_usesGenericGreeting() {
        Alert alert = alert("payment-service", "CRITICAL", 5);
        String html = builder.buildAlertEmail(alert, "   ");

        assertThat(html).contains("Hello,");
    }

    @Test
    void buildAlertEmail_htmlSpecialCharsInMessage_areEscaped() {
        Alert alert = new Alert("payment-service",
                "<script>alert('xss')</script>", 1, "WARNING", Instant.now());
        String html = builder.buildAlertEmail(alert, "Dev");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void buildAlertEmail_htmlSpecialCharsInServiceName_areEscaped() {
        Alert alert = new Alert("<evil>", "message", 1, "WARNING", Instant.now());
        String html = builder.buildAlertEmail(alert, "Dev");

        assertThat(html).doesNotContain("<evil>");
        assertThat(html).contains("&lt;evil&gt;");
    }

    @Test
    void buildAlertEmail_containsErrorCount() {
        Alert alert = alert("payment-service", "CRITICAL", 42);
        String html = builder.buildAlertEmail(alert, "Dev");

        assertThat(html).contains("42");
    }

    @Test
    void buildAlertEmail_isValidHtml() {
        Alert alert = alert("payment-service", "CRITICAL", 5);
        String html = builder.buildAlertEmail(alert, "Dev");

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("</html>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRITICAL", "ERROR", "WARNING", "INFO"})
    void buildAlertEmail_allSeverityLevels_producesNonEmptyHtml(String severity) {
        Alert alert = alert("svc", severity, 1);
        String html = builder.buildAlertEmail(alert, "User");

        assertThat(html).isNotBlank();
        assertThat(html).contains(severity);
    }

    // ── buildPlainTextEmail ───────────────────────────────────────────────────

    @Test
    void buildPlainTextEmail_containsAllAlertFields() {
        Alert alert = alert("payment-service", "CRITICAL", 7);
        String text = builder.buildPlainTextEmail(alert, "Dev User");

        assertThat(text).contains("payment-service");
        assertThat(text).contains("CRITICAL");
        assertThat(text).contains("7");
        assertThat(text).contains("Dev User");
    }

    @Test
    void buildPlainTextEmail_nullRecipientName_usesGenericGreeting() {
        Alert alert = alert("payment-service", "WARNING", 2);
        String text = builder.buildPlainTextEmail(alert, null);

        assertThat(text).contains("Hello,");
    }

    @Test
    void buildPlainTextEmail_containsSuggestedAction() {
        Alert alert = alert("payment-service", "CRITICAL", 5);
        String text = builder.buildPlainTextEmail(alert, "Dev");

        assertThat(text).containsIgnoringCase("Immediate action");
    }

    @Test
    void buildPlainTextEmail_warningAlert_containsMonitorSuggestion() {
        Alert alert = alert("auth-service", "WARNING", 1);
        String text = builder.buildPlainTextEmail(alert, "Dev");

        assertThat(text).containsIgnoringCase("Monitor");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Alert alert(String service, String severity, int count) {
        return new Alert(service, service + " has " + count + " errors", count, severity, Instant.now());
    }
}
