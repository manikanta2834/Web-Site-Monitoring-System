package com.wsms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class WebsiteCreateDto {

    @NotBlank(message = "Website name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String websiteName;

    @NotBlank(message = "Website URL is required")
    @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
    private String websiteUrl;

    @NotNull(message = "Check interval is required")
    private Integer checkInterval = 60; // Default 60 seconds

    // Constructors
    public WebsiteCreateDto() {}

    public WebsiteCreateDto(String websiteName, String websiteUrl, Integer checkInterval) {
        this.websiteName = websiteName;
        this.websiteUrl = websiteUrl;
        this.checkInterval = checkInterval;
    }

    // Getters and Setters
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
}
