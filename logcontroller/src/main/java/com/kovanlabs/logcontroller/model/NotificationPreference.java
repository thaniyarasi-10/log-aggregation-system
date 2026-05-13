package com.kovanlabs.logcontroller.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores per-user email notification preferences.
 *
 * <p>One row per user. Created on first access (upsert semantics in the service layer).
 * The only user-configurable setting is the master email switch — all other delivery
 * behaviour (cooldown, severity filtering) is controlled globally via application config.
 */
@Entity
@Table(name = "notification_preference",
        uniqueConstraints = @UniqueConstraint(name = "uq_notif_pref_user", columnNames = "user_id"))
public class NotificationPreference {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false)
    private UUID id;

    /** FK to the {@code "user"} table. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Master switch — when false no emails are sent for this user. */
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
