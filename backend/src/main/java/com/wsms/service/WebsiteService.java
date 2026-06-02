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

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WebsiteService {

    private final WebsiteRepository websiteRepository;
    private final MonitoringLogRepository logRepository;
    private final MonitoringService monitoringService;

    @Autowired
    public WebsiteService(WebsiteRepository websiteRepository, 
                          MonitoringLogRepository logRepository,
                          MonitoringService monitoringService) {
        this.websiteRepository = websiteRepository;
        this.logRepository = logRepository;
        this.monitoringService = monitoringService;
    }

    /**
     * Fetch all monitored websites along with historical logs and aggregated uptimes.
     */
    @Transactional(readOnly = true)
    public List<WebsiteResponseDto> getAllWebsites() {
        return websiteRepository.findAll().stream()
                .map(this::convertToResponseDto)
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
        Website saved = websiteRepository.save(website);

        // 4. Trigger Pre-Flight Check immediately in background
        monitoringService.probeWebsiteAsync(saved.getId());

        return convertToResponseDto(saved);
    }

    /**
     * Delete website configuration and purge all historical logs.
     */
    @Transactional
    public void deleteWebsite(Integer id) {
        if (!websiteRepository.existsById(id)) {
            throw new ResourceNotFoundException("Cannot delete website. Website not found with ID: " + id);
        }
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
