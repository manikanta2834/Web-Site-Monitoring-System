package com.wsms.controller;

import com.wsms.entity.CriticalLog;
import com.wsms.repository.CriticalLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/critical-logs")
@CrossOrigin(origins = "*")
public class CriticalLogController {

    private final CriticalLogRepository criticalLogRepository;

    @Autowired
    public CriticalLogController(CriticalLogRepository criticalLogRepository) {
        this.criticalLogRepository = criticalLogRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<CriticalLog>> getAllLogs(@RequestParam(required = false) Boolean resolved) {
        if (resolved != null) {
            return ResponseEntity.ok(criticalLogRepository.findByResolved(resolved));
        }
        return ResponseEntity.ok(criticalLogRepository.findAll());
    }

    @GetMapping("/website/{websiteId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<List<CriticalLog>> getLogsByWebsite(@PathVariable Integer websiteId) {
        return ResponseEntity.ok(criticalLogRepository.findByWebsiteId(websiteId));
    }

    @PutMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<CriticalLog> resolveLog(@PathVariable Long id) {
        CriticalLog log = criticalLogRepository.findById(id).orElse(null);
        if (log == null) {
            return ResponseEntity.notFound().build();
        }
        log.setResolved(true);
        log.setResolvedAt(Instant.now());
        CriticalLog updated = criticalLogRepository.save(log);
        return ResponseEntity.ok(updated);
    }
}
