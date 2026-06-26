package com.wsms.service;

import com.wsms.dto.PortalCustomizationCreateDto;
import com.wsms.dto.PortalCustomizationResponseDto;
import com.wsms.entity.PortalCustomization;
import com.wsms.repository.PortalCustomizationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PortalCustomizationService {

    private final PortalCustomizationRepository repository;

    @Autowired
    public PortalCustomizationService(PortalCustomizationRepository repository) {
        this.repository = repository;
    }

    public PortalCustomizationResponseDto getActiveCustomization() {
        PortalCustomization entity = repository.findFirstByIsActiveTrue()
                .orElseGet(() -> new PortalCustomization(
                        "Default Layout",
                        "WSMS",
                        "WSMS Standalone - Advanced Monitoring & Observability Platform",
                        "Observability Gateway & Observational Control",
                        "/src/assets/logo.svg",
                        true
                ));
        return mapToDto(entity);
    }

    public List<PortalCustomizationResponseDto> getAllCustomizations() {
        return repository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public PortalCustomizationResponseDto getCustomizationById(Integer id) {
        PortalCustomization entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Portal customization not found with id: " + id));
        return mapToDto(entity);
    }

    @Transactional
    public PortalCustomizationResponseDto createCustomization(PortalCustomizationCreateDto dto) {
        if (repository.findByName(dto.getName()).isPresent()) {
            throw new IllegalArgumentException("Customization layout name must be unique: " + dto.getName());
        }
        PortalCustomization entity = new PortalCustomization(
                dto.getName(),
                dto.getHeaderText(),
                dto.getFooterText(),
                dto.getBodyContent(),
                dto.getLogoUrl(),
                false
        );
        PortalCustomization saved = repository.save(entity);
        return mapToDto(saved);
    }

    @Transactional
    public PortalCustomizationResponseDto updateCustomization(Integer id, PortalCustomizationCreateDto dto) {
        PortalCustomization entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Portal customization not found with id: " + id));
        
        repository.findByName(dto.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("Another customization layout already has the name: " + dto.getName());
            }
        });

        entity.setName(dto.getName());
        entity.setHeaderText(dto.getHeaderText());
        entity.setFooterText(dto.getFooterText());
        entity.setBodyContent(dto.getBodyContent());
        entity.setLogoUrl(dto.getLogoUrl());

        PortalCustomization updated = repository.save(entity);
        return mapToDto(updated);
    }

    @Transactional
    public void deleteCustomization(Integer id) {
        PortalCustomization entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Portal customization not found with id: " + id));
        if (entity.getIsActive()) {
            throw new IllegalArgumentException("Cannot delete the currently active portal layout customization. Activate another layout first.");
        }
        repository.delete(entity);
    }

    @Transactional
    public PortalCustomizationResponseDto activateCustomization(Integer id) {
        PortalCustomization entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Portal customization not found with id: " + id));

        // Set all other customizations to inactive
        List<PortalCustomization> all = repository.findAll();
        for (PortalCustomization custom : all) {
            custom.setIsActive(custom.getId().equals(id));
        }
        repository.saveAll(all);

        return mapToDto(entity);
    }

    private PortalCustomizationResponseDto mapToDto(PortalCustomization entity) {
        return new PortalCustomizationResponseDto(
                entity.getId(),
                entity.getName(),
                entity.getHeaderText(),
                entity.getFooterText(),
                entity.getBodyContent(),
                entity.getLogoUrl(),
                entity.getIsActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
