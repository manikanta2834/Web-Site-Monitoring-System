package com.wsms.repository;

import com.wsms.entity.CriticalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CriticalLogRepository extends JpaRepository<CriticalLog, Long> {
    List<CriticalLog> findByResolved(Boolean resolved);
    List<CriticalLog> findByWebsiteId(Integer websiteId);
    List<CriticalLog> findByResolvedAndSeverity(Boolean resolved, String severity);
}
