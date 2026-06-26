package com.wsms.service;

import com.wsms.dto.WebsiteCreateDto;
import com.wsms.dto.WebsiteResponseDto;
import com.wsms.entity.MonitoringLog;
import com.wsms.entity.Website;
import com.wsms.exception.ResourceNotFoundException;
import com.wsms.repository.MonitoringLogRepository;
import com.wsms.repository.WebsiteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WebsiteService {

    private final WebsiteRepository websiteRepository;
    private final MonitoringLogRepository logRepository;
    private final MonitoringService monitoringService;
    private final SystemSettingService settingService;

    @Autowired
    public WebsiteService(WebsiteRepository websiteRepository, 
                          MonitoringLogRepository logRepository,
                          MonitoringService monitoringService,
                          SystemSettingService settingService) {
        this.websiteRepository = websiteRepository;
        this.logRepository = logRepository;
        this.monitoringService = monitoringService;
        this.settingService = settingService;
    }

    /**
     * Fetch all monitored websites along with historical logs and aggregated uptimes.
     */
    @Transactional(readOnly = true)
    public List<WebsiteResponseDto> getAllWebsites() {
        return websiteRepository.findAll().stream()
                .map(this::convertToResponseDto)
                .sorted((a, b) -> {
                    boolean aDown = "DOWN".equalsIgnoreCase(a.getStatus());
                    boolean bDown = "DOWN".equalsIgnoreCase(b.getStatus());
                    if (aDown && !bDown) return -1;
                    if (!aDown && bDown) return 1;

                    boolean aUp = "UP".equalsIgnoreCase(a.getStatus());
                    boolean bUp = "UP".equalsIgnoreCase(b.getStatus());
                    if (aUp && !bUp) return -1;
                    if (!aUp && bUp) return 1;

                    int nameCompare = a.getWebsiteName().compareToIgnoreCase(b.getWebsiteName());
                    if (nameCompare != 0) {
                        return nameCompare;
                    }
                    return a.getId().compareTo(b.getId());
                })
                .collect(Collectors.toList());
    }

    /**
     * Fetch a single monitored website's complete aggregated metrics.
     */
    @Transactional(readOnly = true)
    public WebsiteResponseDto getWebsiteById(Integer id) {
        Website website = websiteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Website not found with ID: " + id));
        return convertToResponseDto(website);
    }

    /**
     * Adds a new website configuration under standard MVP constraints.
     */
    @Transactional
    public WebsiteResponseDto createWebsite(WebsiteCreateDto dto) {
        // 1. Enforce MVP Limit of 10 Websites
        if (websiteRepository.count() >= 10) {
            throw new IllegalArgumentException("Web Sites Monitoring System (MVP) limit reached. You can only monitor up to 10 websites.");
        }

        // 2. Validate URL uniqueness
        String cleanUrl = dto.getWebsiteUrl().trim();
        if (websiteRepository.existsByWebsiteUrl(cleanUrl)) {
            throw new IllegalArgumentException("A website with URL '" + cleanUrl + "' is already registered for monitoring.");
        }

        // 3. Save to database
        Website website = new Website(
                dto.getWebsiteName().trim(),
                cleanUrl,
                dto.getCheckInterval()
        );

        int globalTimeout = settingService.getSettingValueAsInt("global_connection_timeout", 5000);
        int globalRetry = settingService.getSettingValueAsInt("global_retry_count", 3);
        int globalSsl = settingService.getSettingValueAsInt("global_ssl_expiry_threshold", 30);
        double globalDns = settingService.getSettingValueAsDouble("global_dns_lookup_threshold", 150.0);
        double globalEwma = settingService.getSettingValueAsDouble("global_ewma_threshold", 500.0);

        boolean customTimeout = dto.getConnectionTimeout() != null && !dto.getConnectionTimeout().equals(globalTimeout);
        boolean customRetry = dto.getRetryCount() != null && !dto.getRetryCount().equals(globalRetry);
        boolean customSsl = dto.getSslExpiryThreshold() != null && !dto.getSslExpiryThreshold().equals(globalSsl);
        boolean customDns = dto.getDnsLookupThreshold() != null && !dto.getDnsLookupThreshold().equals(globalDns);
        boolean customEwma = dto.getEwmaThreshold() != null && !dto.getEwmaThreshold().equals(globalEwma);

        if (customTimeout || customRetry || customSsl || customDns || customEwma) {
            website.setThreshold(new com.wsms.entity.WebsiteThreshold(website, 
                dto.getSslExpiryThreshold() != null ? dto.getSslExpiryThreshold() : globalSsl, 
                dto.getDnsLookupThreshold() != null ? dto.getDnsLookupThreshold() : globalDns, 
                dto.getEwmaThreshold() != null ? dto.getEwmaThreshold() : globalEwma, 
                dto.getConnectionTimeout() != null ? dto.getConnectionTimeout() : globalTimeout, 
                dto.getRetryCount() != null ? dto.getRetryCount() : globalRetry));
        }

        Website saved = websiteRepository.save(website);

        // 4. Trigger Pre-Flight Check immediately in background
        monitoringService.probeWebsiteAsync(saved.getId());

        return convertToResponseDto(saved);
    }

    /**
     * Updates an existing website configuration.
     */
    @Transactional
    public WebsiteResponseDto updateWebsite(Integer id, WebsiteCreateDto dto) {
        Website website = websiteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Website not found with ID: " + id));

        String cleanUrl = dto.getWebsiteUrl().trim();
        
        // Validate URL uniqueness if changed
        if (!website.getWebsiteUrl().equalsIgnoreCase(cleanUrl)) {
            if (websiteRepository.existsByWebsiteUrl(cleanUrl)) {
                throw new IllegalArgumentException("A website with URL '" + cleanUrl + "' is already registered.");
            }
            // URL has changed: reset state to PENDING and trigger pre-flight check
            website.setWebsiteUrl(cleanUrl);
            website.setStatus("PENDING");
            website.setResponseTime(0.0);
            website.setEwmaResponseTime(0.0);
            website.setSslExpiryDate(null);
            
            // Trigger pre-flight check immediately
            monitoringService.probeWebsiteAsync(id);
        }

        website.setWebsiteName(dto.getWebsiteName().trim());
        website.setCheckInterval(dto.getCheckInterval());
        
        int globalTimeout = settingService.getSettingValueAsInt("global_connection_timeout", 5000);
        int globalRetry = settingService.getSettingValueAsInt("global_retry_count", 3);
        int globalSsl = settingService.getSettingValueAsInt("global_ssl_expiry_threshold", 30);
        double globalDns = settingService.getSettingValueAsDouble("global_dns_lookup_threshold", 150.0);
        double globalEwma = settingService.getSettingValueAsDouble("global_ewma_threshold", 500.0);

        boolean customTimeout = dto.getConnectionTimeout() != null && !dto.getConnectionTimeout().equals(globalTimeout);
        boolean customRetry = dto.getRetryCount() != null && !dto.getRetryCount().equals(globalRetry);
        boolean customSsl = dto.getSslExpiryThreshold() != null && !dto.getSslExpiryThreshold().equals(globalSsl);
        boolean customDns = dto.getDnsLookupThreshold() != null && !dto.getDnsLookupThreshold().equals(globalDns);
        boolean customEwma = dto.getEwmaThreshold() != null && !dto.getEwmaThreshold().equals(globalEwma);

        if (customTimeout || customRetry || customSsl || customDns || customEwma) {
            com.wsms.entity.WebsiteThreshold threshold = website.getThreshold();
            if (threshold == null) {
                threshold = new com.wsms.entity.WebsiteThreshold();
                threshold.setWebsite(website);
                website.setThreshold(threshold);
            }
            threshold.setSslExpiryThreshold(dto.getSslExpiryThreshold() != null ? dto.getSslExpiryThreshold() : globalSsl);
            threshold.setDnsLookupThreshold(dto.getDnsLookupThreshold() != null ? dto.getDnsLookupThreshold() : globalDns);
            threshold.setEwmaThreshold(dto.getEwmaThreshold() != null ? dto.getEwmaThreshold() : globalEwma);
            threshold.setConnectionTimeout(dto.getConnectionTimeout() != null ? dto.getConnectionTimeout() : globalTimeout);
            threshold.setRetryCount(dto.getRetryCount() != null ? dto.getRetryCount() : globalRetry);
        } else {
            // Remove custom threshold override so the website falls back to global settings
            website.setThreshold(null);
        }

        Website updated = websiteRepository.save(website);
        return convertToResponseDto(updated);
    }

    @Transactional
    public void updateWebsiteStatus(Integer id, String status) {
        Website website = websiteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Website not found with ID: " + id));
        website.setStatus(status);
        websiteRepository.save(website);
    }

    /**
     * Delete website configuration and purge all historical logs.
     */
    @Transactional
    public void deleteWebsite(Integer id) {
        if (!websiteRepository.existsById(id)) {
            throw new ResourceNotFoundException("Cannot delete website. Website not found with ID: " + id);
        }
        // First delete all historical logs to satisfy the foreign key constraint
        logRepository.deleteByWebsiteId(id);
        
        // Then delete the website configuration
        websiteRepository.deleteById(id);
    }

    /**
     * Mappings helper to convert JPA Entity to fully aggregated DTO.
     */
    private WebsiteResponseDto convertToResponseDto(Website w) {
        WebsiteResponseDto dto = new WebsiteResponseDto();
        dto.setId(w.getId());
        dto.setWebsiteName(w.getWebsiteName());
        dto.setWebsiteUrl(w.getWebsiteUrl());
        dto.setCheckInterval(w.getCheckInterval());
        dto.setStatus(w.getStatus());
        dto.setResponseTime(w.getResponseTime());
        dto.setEwmaResponseTime(w.getEwmaResponseTime());
        dto.setSslExpiryDate(w.getSslExpiryDate());
        dto.setDnsLookupTime(w.getDnsLookupTime());
        dto.setSslIssuer(w.getSslIssuer());
        dto.setProtocol(w.getProtocol());
        if (w.getThreshold() != null) {
            dto.setSslExpiryThreshold(w.getThreshold().getSslExpiryThreshold());
            dto.setDnsLookupThreshold(w.getThreshold().getDnsLookupThreshold());
            dto.setEwmaThreshold(w.getThreshold().getEwmaThreshold());
            dto.setConnectionTimeout(w.getThreshold().getConnectionTimeout());
            dto.setRetryCount(w.getThreshold().getRetryCount());
        } else {
            dto.setSslExpiryThreshold(settingService.getSettingValueAsInt("global_ssl_expiry_threshold", 30));
            dto.setDnsLookupThreshold(settingService.getSettingValueAsDouble("global_dns_lookup_threshold", 150.0));
            dto.setEwmaThreshold(settingService.getSettingValueAsDouble("global_ewma_threshold", 500.0));
            dto.setConnectionTimeout(settingService.getSettingValueAsInt("global_connection_timeout", 5000));
            dto.setRetryCount(settingService.getSettingValueAsInt("global_retry_count", 3));
        }

        // 1. Compute Uptime Percentage
        long totalChecks = logRepository.countByWebsiteId(w.getId());
        long upChecks = logRepository.countByWebsiteIdAndStatus(w.getId(), "UP");
        if (totalChecks > 0) {
            double uptime = ((double) upChecks / totalChecks) * 100.0;
            // Round to 2 decimal places
            dto.setUptimePercentage(Math.round(uptime * 100.0) / 100.0);
        } else {
            dto.setUptimePercentage(100.0); // Default to 100% on new items
        }

        // 2. Retrieve recent 20 logs for historical charting
        List<MonitoringLog> recentLogs = logRepository.findRecentLogs(w.getId(), PageRequest.of(0, 20));
        
        List<WebsiteResponseDto.LogDto> logDtos = recentLogs.stream()
                .map(l -> new WebsiteResponseDto.LogDto(
                        l.getId(),
                        l.getStatusCode(),
                        l.getResponseTime(),
                        l.getStatus(),
                        l.getCheckedAt()
                ))
                .collect(Collectors.toList());
        dto.setRecentLogs(logDtos);

        // 3. Resolve last checked timestamp
        if (!recentLogs.isEmpty()) {
            dto.setLastCheckedTime(recentLogs.get(0).getCheckedAt());
        } else {
            dto.setLastCheckedTime(w.getCreatedAt()); // Fallback to created time
        }

        return dto;
    }
}
