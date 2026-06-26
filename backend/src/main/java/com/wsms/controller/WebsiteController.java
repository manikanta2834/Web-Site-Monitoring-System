package com.wsms.controller;

import com.wsms.dto.WebsiteCreateDto;
import com.wsms.dto.WebsiteResponseDto;
import com.wsms.service.MonitoringService;
import com.wsms.service.WebsiteService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/websites")
@CrossOrigin(origins = "*") // Enable CORS for development
public class WebsiteController {

    private final WebsiteService websiteService;
    private final MonitoringService monitoringService;

    @Autowired
    public WebsiteController(WebsiteService websiteService, MonitoringService monitoringService) {
        this.websiteService = websiteService;
        this.monitoringService = monitoringService;
    }

    /**
     * Get all monitored websites and their metrics.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<WebsiteResponseDto>> getAllWebsites() {
        List<WebsiteResponseDto> list = websiteService.getAllWebsites();
        return ResponseEntity.ok(list);
    }

    /**
     * Get detailed logs and settings for a specific website.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<WebsiteResponseDto> getWebsiteById(@PathVariable Integer id) {
        WebsiteResponseDto dto = websiteService.getWebsiteById(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * Register a new website for active monitoring.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<WebsiteResponseDto> createWebsite(@Valid @RequestBody WebsiteCreateDto dto) {
        WebsiteResponseDto created = websiteService.createWebsite(dto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Update an existing website configuration.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<WebsiteResponseDto> updateWebsite(
            @PathVariable Integer id,
            @Valid @RequestBody WebsiteCreateDto dto) {
        WebsiteResponseDto updated = websiteService.updateWebsite(id, dto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deregister a website and purge its data.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteWebsite(@PathVariable Integer id) {
        websiteService.deleteWebsite(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Manually trigger an immediate background probe.
     * Helpful to skip the 60-second queue for testing!
     */
    @PostMapping("/{id}/check")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<String> forceWebsiteCheck(@PathVariable Integer id) {
        WebsiteResponseDto website = websiteService.getWebsiteById(id);
        if ("PENDING".equalsIgnoreCase(website.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("A check is already in progress for this website.");
        }
        
        // Set status to PENDING immediately so the UI reflects the refreshing state
        websiteService.updateWebsiteStatus(id, "PENDING");
        
        // Trigger Async probe
        monitoringService.probeWebsiteAsync(id);
        
        return ResponseEntity.ok("Website check triggered successfully in the background.");
    }
}
