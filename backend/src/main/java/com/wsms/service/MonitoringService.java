package com.wsms.service;

import com.wsms.entity.MonitoringLog;
import com.wsms.entity.Website;
import com.wsms.repository.MonitoringLogRepository;
import com.wsms.repository.WebsiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;

@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final WebsiteRepository websiteRepository;
    private final MonitoringLogRepository logRepository;
    private final HttpClient httpClient;

    @Value("${wsms.probing.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${wsms.probing.retry-count:3}")
    private int maxRetries;

    @Value("${wsms.probing.retry-delay-ms:8000}")
    private int retryDelayMs;

    private static final double EWMA_ALPHA = 0.3; // Alpha smoothing factor

    @Autowired
    public MonitoringService(WebsiteRepository websiteRepository, MonitoringLogRepository logRepository) {
        this.websiteRepository = websiteRepository;
        this.logRepository = logRepository;
        
        // Build efficient HttpClient with configured connection timeout
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Entry point to probe all websites.
     * This iterates through active websites and spawns async threads for each check.
     */
    @Transactional
    public void triggerAllChecks() {
        var websites = websiteRepository.findAll();
        log.info("Triggering monitoring cycle for {} websites...", websites.size());
        for (Website website : websites) {
            // Self-invocation of Async method does not trigger Spring AOP proxy, 
            // but we call it from our scheduler, which does.
            // Executing checks in parallel asynchronously.
            probeWebsiteAsync(website.getId());
        }
    }

    /**
     * Asynchronously probes a specific website with robust retry mechanisms, EWMA calculation,
     * SSL expiry checks, and time-series database log ledger writing.
     */
    @Async("probingExecutor")
    @Transactional
    public void probeWebsiteAsync(Integer websiteId) {
        Website website = websiteRepository.findById(websiteId).orElse(null);
        if (website == null) {
            return;
        }

        log.debug("Starting async probe for website: {} ({})", website.getWebsiteName(), website.getWebsiteUrl());

        int attempts = 0;
        boolean isUp = false;
        int lastStatusCode = 0;
        long totalDurationMs = 0;

        // Perform probe try + configured retries
        while (attempts <= maxRetries) {
            attempts++;
            long startTime = System.currentTimeMillis();
            
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(website.getWebsiteUrl()))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("User-Agent", "WSMS-Observability-Agent/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                totalDurationMs = System.currentTimeMillis() - startTime;
                lastStatusCode = response.statusCode();

                // Rule: If HTTP status code = 200 -> UP, Else -> DOWN
                if (lastStatusCode == 200) {
                    isUp = true;
                    break; // Success! Break out of retry loop
                } else {
                    log.warn("Probe failed for {} (Attempt {}/{}). Status Code: {}", 
                            website.getWebsiteUrl(), attempts, maxRetries + 1, lastStatusCode);
                }
            } catch (Exception e) {
                totalDurationMs = System.currentTimeMillis() - startTime;
                lastStatusCode = 0; // Network exception / Timeout
                log.warn("Probe failed for {} (Attempt {}/{}). Error: {}", 
                        website.getWebsiteUrl(), attempts, maxRetries + 1, e.getMessage());
            }

            // If we have retries left, wait before making the next attempt
            if (attempts <= maxRetries && !isUp) {
                try {
                    log.info("Sleeping {}ms before retry attempt {} for {}", retryDelayMs, attempts + 1, website.getWebsiteName());
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Probe retry thread interrupted for website: {}", website.getWebsiteName());
                    break;
                }
            }
        }

        // 1. Process Uptime Status
        String finalStatus = isUp ? "UP" : "DOWN";
        double responseTime = (double) totalDurationMs;

        // 2. Calculate EWMA (Exponentially Weighted Moving Average) Response Time
        double previousEwma = website.getEwmaResponseTime();
        double finalEwma;
        if (previousEwma <= 0.0) {
            finalEwma = responseTime; // Initialize with current if first run
        } else {
            finalEwma = (EWMA_ALPHA * responseTime) + ((1.0 - EWMA_ALPHA) * previousEwma);
        }

        // 3. SSL Expiration Parsing (if website starts with https)
        Instant sslExpiry = null;
        if (website.getWebsiteUrl().toLowerCase().startsWith("https://")) {
            sslExpiry = checkSslCertificate(website.getWebsiteUrl());
        }

        // 4. Update Website state
        website.setStatus(finalStatus);
        website.setResponseTime(responseTime);
        website.setEwmaResponseTime(finalEwma);
        if (sslExpiry != null) {
            website.setSslExpiryDate(sslExpiry);
        }
        websiteRepository.save(website);

        // 5. Write to PostgreSQL Time-Series Log Table
        MonitoringLog checkLog = new MonitoringLog(website, lastStatusCode, (int) responseTime, finalStatus);
        logRepository.save(checkLog);

        log.info("Probe completed: {} | Status: {} | Latency: {}ms | EWMA: {}ms | SSL Expiry: {}",
                website.getWebsiteName(), finalStatus, responseTime, Math.round(finalEwma), sslExpiry);
    }

    /**
     * Extracts SSL/TLS Certificate Expiration Date by executing a secure TCP socket handshake.
     */
    private Instant checkSslCertificate(String urlString) {
        SSLSocket socket = null;
        try {
            URI uri = new URI(urlString);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                port = 443; // default HTTPS port
            }

            if (host == null) {
                return null;
            }

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            // Open secure socket with a 3-second connection timeout
            socket = (SSLSocket) factory.createSocket();
            socket.connect(new java.net.InetSocketAddress(host, port), 3000);
            socket.startHandshake();

            Certificate[] certs = socket.getSession().getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate) {
                X509Certificate cert = (X509Certificate) certs[0];
                return cert.getNotAfter().toInstant();
            }
        } catch (Exception e) {
            log.warn("Could not extract SSL certificate for {}: {}", urlString, e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
