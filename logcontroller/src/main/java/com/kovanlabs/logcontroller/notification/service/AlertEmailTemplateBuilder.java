package com.kovanlabs.logcontroller.notification.service;

import com.kovanlabs.logcontroller.model.Alert;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds professional HTML email bodies for alert notifications.
 *
 * <p>Templates are self-contained (inline CSS only — no external resources) so they
 * render correctly in all major email clients including Outlook, Gmail, and Apple Mail.
 *
 * <p>Severity colours:
 * <ul>
 *   <li>CRITICAL — #dc2626 (red)</li>
 *   <li>ERROR    — #ea580c (orange-red)</li>
 *   <li>WARNING  — #d97706 (amber)</li>
 *   <li>INFO     — #2563eb (blue)</li>
 * </ul>
 */
@Component
public class AlertEmailTemplateBuilder {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneId.of("UTC"));

    /**
     * Builds the HTML body for an alert notification email.
     *
     * @param alert         the alert to notify about
     * @param recipientName display name of the recipient (used in the greeting)
     * @return complete HTML string ready to be set as the email body
     */
    public String buildAlertEmail(Alert alert, String recipientName) {
        String severity      = alert.getSeverity() != null ? alert.getSeverity().toUpperCase() : "UNKNOWN";
        String service       = escapeHtml(alert.getService());
        String message       = escapeHtml(alert.getMessage());
        String timestamp     = alert.getTimestamp() != null ? FORMATTER.format(alert.getTimestamp()) : "N/A";
        String badgeColor    = severityColor(severity);
        String badgeBg       = severityBgColor(severity);
        String suggestion    = suggestedAction(severity, alert.getService());
        String greeting      = recipientName != null && !recipientName.isBlank()
                ? "Hi " + escapeHtml(recipientName) + ","
                : "Hello,";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Alert Notification</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f3f4f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f3f4f6;padding:32px 0;">
                    <tr>
                      <td align="center">
                        <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                          <!-- Header -->
                          <tr>
                            <td style="background-color:#0f172a;border-radius:8px 8px 0 0;padding:24px 32px;">
                              <table width="100%%" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td>
                                    <span style="color:#f8fafc;font-size:20px;font-weight:700;letter-spacing:-0.5px;">
                                      &#9888; LogController
                                    </span>
                                    <span style="color:#94a3b8;font-size:13px;margin-left:8px;">Alert Notification</span>
                                  </td>
                                  <td align="right">
                                    <span style="background-color:%s;color:%s;font-size:12px;font-weight:700;
                                                 padding:4px 12px;border-radius:9999px;letter-spacing:0.5px;">
                                      %s
                                    </span>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>

                          <!-- Body -->
                          <tr>
                            <td style="background-color:#ffffff;padding:32px;">

                              <p style="margin:0 0 16px;color:#374151;font-size:15px;">%s</p>
                              <p style="margin:0 0 24px;color:#6b7280;font-size:14px;line-height:1.6;">
                                An alert has been triggered on your platform. Please review the details below.
                              </p>

                              <!-- Alert details card -->
                              <table width="100%%" cellpadding="0" cellspacing="0"
                                     style="background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;margin-bottom:24px;">
                                <tr>
                                  <td style="padding:20px 24px;">
                                    <table width="100%%" cellpadding="0" cellspacing="8">
                                      %s
                                    </table>
                                  </td>
                                </tr>
                              </table>

                              <!-- Suggested action -->
                              <table width="100%%" cellpadding="0" cellspacing="0"
                                     style="background-color:#fffbeb;border:1px solid #fde68a;border-radius:8px;margin-bottom:24px;">
                                <tr>
                                  <td style="padding:16px 20px;">
                                    <p style="margin:0 0 4px;color:#92400e;font-size:13px;font-weight:600;">
                                      &#128161; Suggested Action
                                    </p>
                                    <p style="margin:0;color:#78350f;font-size:13px;line-height:1.5;">%s</p>
                                  </td>
                                </tr>
                              </table>

                              <p style="margin:0;color:#9ca3af;font-size:12px;line-height:1.5;">
                                You are receiving this email because you have <strong>alerts:read</strong> permission
                                on the LogController platform. To manage your notification preferences, visit the
                                dashboard settings.
                              </p>
                            </td>
                          </tr>

                          <!-- Footer -->
                          <tr>
                            <td style="background-color:#f8fafc;border-top:1px solid #e2e8f0;
                                       border-radius:0 0 8px 8px;padding:16px 32px;">
                              <p style="margin:0;color:#9ca3af;font-size:11px;text-align:center;">
                                LogController &bull; Log Aggregation &amp; Monitoring Platform &bull;
                                This is an automated notification. Do not reply to this email.
                              </p>
                            </td>
                          </tr>

                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                badgeBg, badgeColor, severity,
                greeting,
                buildDetailRows(service, severity, timestamp, alert.getCount(), message),
                suggestion
        );
    }

    /**
     * Builds the plain-text fallback for email clients that don't render HTML.
     */
    public String buildPlainTextEmail(Alert alert, String recipientName) {
        String timestamp = alert.getTimestamp() != null ? FORMATTER.format(alert.getTimestamp()) : "N/A";
        String greeting  = recipientName != null && !recipientName.isBlank()
                ? "Hi " + recipientName + ","
                : "Hello,";

        return """
                %s
                
                An alert has been triggered on your platform.
                
                ─────────────────────────────────────────
                ALERT DETAILS
                ─────────────────────────────────────────
                Service   : %s
                Severity  : %s
                Timestamp : %s
                Error Count: %d
                Message   : %s
                ─────────────────────────────────────────
                
                Suggested Action:
                %s
                
                ─────────────────────────────────────────
                LogController — Log Aggregation & Monitoring Platform
                This is an automated notification. Do not reply.
                """.formatted(
                greeting,
                alert.getService(),
                alert.getSeverity(),
                timestamp,
                alert.getCount(),
                alert.getMessage(),
                suggestedAction(alert.getSeverity(), alert.getService())
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildDetailRows(String service, String severity, String timestamp,
                                   int count, String message) {
        return detailRow("Service", service) +
               detailRow("Severity", "<strong style=\"color:" + severityTextColor(severity) + ";\">" + severity + "</strong>") +
               detailRow("Timestamp", timestamp) +
               detailRow("Error Count", String.valueOf(count)) +
               detailRow("Message", message);
    }

    private String detailRow(String label, String value) {
        return """
                <tr>
                  <td style="color:#6b7280;font-size:13px;padding:4px 0;width:120px;vertical-align:top;">%s</td>
                  <td style="color:#111827;font-size:13px;padding:4px 0;font-weight:500;">%s</td>
                </tr>
                """.formatted(label, value);
    }

    private String severityColor(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "#ffffff";
            case "ERROR"    -> "#ffffff";
            case "WARNING"  -> "#ffffff";
            default         -> "#ffffff";
        };
    }

    private String severityBgColor(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "#dc2626";
            case "ERROR"    -> "#ea580c";
            case "WARNING"  -> "#d97706";
            default         -> "#2563eb";
        };
    }

    private String severityTextColor(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> "#dc2626";
            case "ERROR"    -> "#ea580c";
            case "WARNING"  -> "#d97706";
            default         -> "#2563eb";
        };
    }

    private String suggestedAction(String severity, String service) {
        String svc = service != null ? service : "the affected service";
        return switch (severity != null ? severity.toUpperCase() : "") {
            case "CRITICAL" -> "Immediate action required. Investigate " + svc +
                    " logs, check service health, and escalate to on-call if the issue persists.";
            case "ERROR"    -> "Review recent deployments and error logs for " + svc +
                    ". Check downstream dependencies and consider rolling back if error rate is rising.";
            case "WARNING"  -> "Monitor " + svc + " closely. Review logs for patterns and " +
                    "investigate if the warning count continues to increase.";
            default         -> "Review the alert details and investigate " + svc + " if necessary.";
        };
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
