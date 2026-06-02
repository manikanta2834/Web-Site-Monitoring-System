package com.wsms.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "monitoring_logs")
public class MonitoringLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "website_id", nullable = false)
    private Website website;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_time")
    private Integer responseTime; // in milliseconds

    @Column(name = "status", nullable = false, length = 10)
    private String status; // UP, DOWN

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    // Constructors
    public MonitoringLog() {}

    public MonitoringLog(Website website, Integer statusCode, Integer responseTime, String status) {
        this.website = website;
        this.statusCode = statusCode;
        this.responseTime = responseTime;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        this.checkedAt = Instant.now();
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

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public Integer getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Integer responseTime) {
        this.responseTime = responseTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }
}
