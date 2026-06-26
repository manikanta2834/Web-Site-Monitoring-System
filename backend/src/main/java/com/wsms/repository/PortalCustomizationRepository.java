package com.wsms.repository;

import com.wsms.entity.PortalCustomization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PortalCustomizationRepository extends JpaRepository<PortalCustomization, Integer> {
    Optional<PortalCustomization> findFirstByIsActiveTrue();
    Optional<PortalCustomization> findByName(String name);
}
