package com.wsms.scheduler;

import com.wsms.service.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final MonitoringService monitoringService;

    @Autowired
    public MonitoringScheduler(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * Automatically triggers active monitoring probes for all websites in the database
     * every 60 seconds.
     */
    @Scheduled(fixedRate = 60000)
    public void runMonitoringCycle() {
        log.info("Starting automated 60-second monitoring checks...");
        try {
            monitoringService.triggerAllChecks();
        } catch (Exception e) {
            log.error("Error occurred during automated scheduled monitoring cycle: {}", e.getMessage(), e);
        }
    }
}
