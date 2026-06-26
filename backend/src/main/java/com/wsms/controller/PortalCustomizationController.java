package com.wsms.controller;

import com.wsms.dto.PortalCustomizationCreateDto;
import com.wsms.dto.PortalCustomizationResponseDto;
import com.wsms.service.PortalCustomizationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/portal-customizations")
@CrossOrigin(origins = "*") // Enable CORS for development
public class PortalCustomizationController {

    private final PortalCustomizationService service;

    @Autowired
    public PortalCustomizationController(PortalCustomizationService service) {
        this.service = service;
    }

    /**
     * Get the active layout customization. Publicly accessible.
     */
    @GetMapping("/active")
    public ResponseEntity<PortalCustomizationResponseDto> getActiveCustomization() {
        return ResponseEntity.ok(service.getActiveCustomization());
    }

    /**
     * Get all customizations.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<PortalCustomizationResponseDto>> getAllCustomizations() {
        return ResponseEntity.ok(service.getAllCustomizations());
    }

    /**
     * Get customization by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<PortalCustomizationResponseDto> getCustomizationById(@PathVariable Integer id) {
        return ResponseEntity.ok(service.getCustomizationById(id));
    }

    /**
     * Create a new customization configuration.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<PortalCustomizationResponseDto> createCustomization(
            @Valid @RequestBody PortalCustomizationCreateDto dto) {
        PortalCustomizationResponseDto created = service.createCustomization(dto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Update an existing customization.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<PortalCustomizationResponseDto> updateCustomization(
            @PathVariable Integer id,
            @Valid @RequestBody PortalCustomizationCreateDto dto) {
        PortalCustomizationResponseDto updated = service.updateCustomization(id, dto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete customization.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteCustomization(@PathVariable Integer id) {
        service.deleteCustomization(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Activate a customization configuration.
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<PortalCustomizationResponseDto> activateCustomization(@PathVariable Integer id) {
        PortalCustomizationResponseDto activated = service.activateCustomization(id);
        return ResponseEntity.ok(activated);
    }
}
