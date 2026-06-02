package com.wsms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@Entity
@Table(name = "websites")
public class Website {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank(message = "Website name cannot be empty")
    @Column(name = "website_name", nullable = false, length = 100)
    private String websiteName;

    @NotBlank(message = "Website URL cannot be empty")
    @Column(name = "website_url", nullable = false, unique = true, length = 255)
    private String websiteUrl;

    @NotNull
    @Column(name = "check_interval", nullable = false)
    private Integer checkInterval = 60; // default 60 seconds

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, UP, DOWN

    @Column(name = "response_time")
    private Double responseTime = 0.0;

    @Column(name = "ewma_response_time")
    private Double ewmaResponseTime = 0.0;

    @Column(name = "ssl_expiry_date")
    private Instant sslExpiryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Constructors
    public Website() {}

    public Website(String websiteName, String websiteUrl, Integer checkInterval) {
        this.websiteName = websiteName;
        this.websiteUrl = websiteUrl;
        this.checkInterval = checkInterval;
    }

    // Lifecycle Hooks for auditing
    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.status == null) {
            this.status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getWebsiteName() {
        return websiteName;
    }

    public void setWebsiteName(String websiteName) {
        this.websiteName = websiteName;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public Integer getCheckInterval() {
        return checkInterval;
    }

    public void setCheckInterval(Integer checkInterval) {
        this.checkInterval = checkInterval;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Double getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Double responseTime) {
        this.responseTime = responseTime;
    }

    public Double getEwmaResponseTime() {
        return ewmaResponseTime;
    }

    public void setEwmaResponseTime(Double ewmaResponseTime) {
        this.ewmaResponseTime = ewmaResponseTime;
    }

    public Instant getSslExpiryDate() {
        return sslExpiryDate;
    }

    public void setSslExpiryDate(Instant sslExpiryDate) {
        this.sslExpiryDate = sslExpiryDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
