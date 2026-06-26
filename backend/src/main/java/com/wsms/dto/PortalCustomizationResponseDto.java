package com.wsms.dto;

import java.time.Instant;

public class PortalCustomizationResponseDto {

    private Integer id;
    private String name;
    private String headerText;
    private String footerText;
    private String bodyContent;
    private String logoUrl;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;

    public PortalCustomizationResponseDto() {}

    public PortalCustomizationResponseDto(Integer id, String name, String headerText, String footerText, String bodyContent, String logoUrl, Boolean isActive, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.headerText = headerText;
        this.footerText = footerText;
        this.bodyContent = bodyContent;
        this.logoUrl = logoUrl;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
