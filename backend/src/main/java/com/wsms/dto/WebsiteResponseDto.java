package com.wsms.dto;

import java.time.Instant;
import java.util.List;

public class WebsiteResponseDto {

    private Integer id;
    private String websiteName;
    private String websiteUrl;
    private Integer checkInterval;
    private String status;
    private Double responseTime;
    private Double ewmaResponseTime;
    private Instant sslExpiryDate;
    private Double uptimePercentage;
    private Instant lastCheckedTime;
    private List<LogDto> recentLogs;

    // Constructors
    public WebsiteResponseDto() {}

    // Static nested class for lightweight log representations
    public static class LogDto {
        private Long id;
        private Integer statusCode;
        private Integer responseTime;
        private String status;
        private Instant checkedAt;

        public LogDto() {}

        public LogDto(Long id, Integer statusCode, Integer responseTime, String status, Instant checkedAt) {
            this.id = id;
            this.statusCode = statusCode;
            this.responseTime = responseTime;
            this.status = status;
            this.checkedAt = checkedAt;
        }

        // Getters and Setters
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
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

    public Double getUptimePercentage() {
        return uptimePercentage;
    }

    public void setUptimePercentage(Double uptimePercentage) {
        this.uptimePercentage = uptimePercentage;
    }

    public Instant getLastCheckedTime() {
        return lastCheckedTime;
    }

    public void setLastCheckedTime(Instant lastCheckedTime) {
        this.lastCheckedTime = lastCheckedTime;
    }

    public List<LogDto> getRecentLogs() {
        return recentLogs;
    }

    public void setRecentLogs(List<LogDto> recentLogs) {
        this.recentLogs = recentLogs;
    }
}
