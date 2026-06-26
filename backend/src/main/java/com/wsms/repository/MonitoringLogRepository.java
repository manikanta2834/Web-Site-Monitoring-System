package com.wsms.repository;

import com.wsms.entity.MonitoringLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MonitoringLogRepository extends JpaRepository<MonitoringLog, Long> {
    
    // Fetch logs for a specific website sorted by time (latest first)
    List<MonitoringLog> findByWebsiteIdOrderByCheckedAtDesc(Integer websiteId);
    
    // Fetch logs for charts with a limit using Pageable
    @Query("SELECT m FROM MonitoringLog m WHERE m.website.id = :websiteId ORDER BY m.checkedAt DESC")
    List<MonitoringLog> findRecentLogs(Integer websiteId, Pageable pageable);
    
    // Count total checks for a website
    long countByWebsiteId(Integer websiteId);
    
    // Count successful checks (UP) to calculate uptime ratio
    long countByWebsiteIdAndStatus(Integer websiteId, String status);

    // Bulk delete logs associated with a website to prevent referential integrity violations
    @Modifying
    @Query("DELETE FROM MonitoringLog m WHERE m.website.id = :websiteId")
    void deleteByWebsiteId(@Param("websiteId") Integer websiteId);
}
