package com.wsms.repository;

import com.wsms.entity.Website;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WebsiteRepository extends JpaRepository<Website, Integer> {
    
    // Check if website URL already exists in our database
    boolean existsByWebsiteUrl(String websiteUrl);
    
    // Find website by its unique URL
    Optional<Website> findByWebsiteUrl(String websiteUrl);
}
