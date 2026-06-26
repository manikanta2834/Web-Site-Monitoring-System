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

    private Integer sslExpiryThreshold;
    private Double dnsLookupThreshold;
    private Double ewmaThreshold;
    private Integer connectionTimeout;
    private Integer retryCount;

    // Constructors
    public WebsiteCreateDto() {}

    public WebsiteCreateDto(String websiteName, String websiteUrl, Integer checkInterval) {
        this.websiteName = websiteName;
        this.websiteUrl = websiteUrl;
        this.checkInterval = checkInterval;
    }

    public WebsiteCreateDto(String websiteName, String websiteUrl, Integer checkInterval, Integer sslExpiryThreshold, Double dnsLookupThreshold, Double ewmaThreshold) {
        this.websiteName = websiteName;
        this.websiteUrl = websiteUrl;
        this.checkInterval = checkInterval;
        this.sslExpiryThreshold = sslExpiryThreshold;
        this.dnsLookupThreshold = dnsLookupThreshold;
        this.ewmaThreshold = ewmaThreshold;
    }

    public WebsiteCreateDto(String websiteName, String websiteUrl, Integer checkInterval, Integer sslExpiryThreshold, Double dnsLookupThreshold, Double ewmaThreshold, Integer connectionTimeout, Integer retryCount) {
        this.websiteName = websiteName;
        this.websiteUrl = websiteUrl;
        this.checkInterval = checkInterval;
        this.sslExpiryThreshold = sslExpiryThreshold;
        this.dnsLookupThreshold = dnsLookupThreshold;
        this.ewmaThreshold = ewmaThreshold;
        this.connectionTimeout = connectionTimeout;
        this.retryCount = retryCount;
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

    public Integer getSslExpiryThreshold() {
        return sslExpiryThreshold;
    }

    public void setSslExpiryThreshold(Integer sslExpiryThreshold) {
        this.sslExpiryThreshold = sslExpiryThreshold;
    }

    public Double getDnsLookupThreshold() {
        return dnsLookupThreshold;
    }

    public void setDnsLookupThreshold(Double dnsLookupThreshold) {
        this.dnsLookupThreshold = dnsLookupThreshold;
    }

    public Double getEwmaThreshold() {
        return ewmaThreshold;
    }

    public void setEwmaThreshold(Double ewmaThreshold) {
        this.ewmaThreshold = ewmaThreshold;
    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
}
