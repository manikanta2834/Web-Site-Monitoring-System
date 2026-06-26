package com.wsms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@Entity
@Table(name = "portal_customizations")
public class PortalCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank(message = "Layout name cannot be empty")
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @NotBlank(message = "Header text cannot be empty")
    @Column(name = "header_text", nullable = false, length = 255)
    private String headerText;

    @NotBlank(message = "Footer text cannot be empty")
    @Column(name = "footer_text", nullable = false, length = 255)
    private String footerText;

    @NotBlank(message = "Body content cannot be empty")
    @Column(name = "body_content", nullable = false, columnDefinition = "TEXT")
    private String bodyContent;

    @NotBlank(message = "Logo URL cannot be empty")
    @Column(name = "logo_url", nullable = false, columnDefinition = "TEXT")
    private String logoUrl;

    @NotNull
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PortalCustomization() {}

    public PortalCustomization(String name, String headerText, String footerText, String bodyContent, String logoUrl, Boolean isActive) {
        this.name = name;
        this.headerText = headerText;
        this.footerText = footerText;
        this.bodyContent = bodyContent;
        this.logoUrl = logoUrl;
        this.isActive = isActive;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.isActive == null) {
            this.isActive = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHeaderText() { return headerText; }
    public void setHeaderText(String headerText) { this.headerText = headerText; }

    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }

    public String getBodyContent() { return bodyContent; }
    public void setBodyContent(String bodyContent) { this.bodyContent = bodyContent; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
