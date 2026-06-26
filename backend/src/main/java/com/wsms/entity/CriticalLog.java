package com.wsms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "critical_logs")
public class CriticalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "website_id", referencedColumnName = "id")
    private Website website;

    @Column(nullable = false, length = 20)
    private String severity; // CRITICAL, ERROR, WARNING

    @Column(name = "error_source", nullable = false, length = 50)
    private String errorSource; // e.g. dns_resolver, ssl_checker, http_client, scheduler

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(nullable = false)
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public CriticalLog() {}

    public CriticalLog(Website website, String severity, String errorSource, String errorMessage, String stackTrace) {
        this.website = website;
        this.severity = severity;
        this.errorSource = errorSource;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.resolved = false;
    }

    @PrePersist
    protected void onCreate() {
        this.occurredAt = Instant.now();
        if (this.resolved == null) {
            this.resolved = false;
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Website getWebsite() {
        return website;
    }

    public void setWebsite(Website website) {
        this.website = website;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getErrorSource() {
        return errorSource;
    }

    public void setErrorSource(String errorSource) {
        this.errorSource = errorSource;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public Boolean getResolved() {
        return resolved;
    }

    public void setResolved(Boolean resolved) {
        this.resolved = resolved;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
