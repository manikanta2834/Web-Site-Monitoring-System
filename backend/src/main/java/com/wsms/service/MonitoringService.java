package com.wsms.service;

import com.wsms.entity.CriticalLog;
import com.wsms.entity.MonitoringLog;
import com.wsms.entity.Website;
import com.wsms.repository.CriticalLogRepository;
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
    private final CriticalLogRepository criticalLogRepository;
    private final SystemSettingService settingService;
    private final HttpClient httpClient;

    @Value("${wsms.probing.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${wsms.probing.retry-count:3}")
    private int maxRetries;

    @Value("${wsms.probing.retry-delay-ms:8000}")
    private int retryDelayMs;

    private static final double EWMA_ALPHA = 0.3; // Alpha smoothing factor

    @Autowired
    public MonitoringService(WebsiteRepository websiteRepository, 
                             MonitoringLogRepository logRepository, 
                             CriticalLogRepository criticalLogRepository, 
                             SystemSettingService settingService) {
        this.websiteRepository = websiteRepository;
        this.logRepository = logRepository;
        this.criticalLogRepository = criticalLogRepository;
        this.settingService = settingService;
        
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
        for (Website website : websites) {
            // Skip disabled websites
            if (website.getEnabled() != null && !website.getEnabled()) {
                continue;
            }
            
            // Respect custom check_interval
            Instant lastChecked = website.getUpdatedAt();
            if (lastChecked == null || lastChecked.plus(Duration.ofSeconds(website.getCheckInterval())).isBefore(Instant.now())) {
                // Self-invocation of Async method does not trigger Spring AOP proxy, 
                // but we call it from our scheduler, which does.
                // Executing checks in parallel asynchronously.
                probeWebsiteAsync(website.getId());
            }
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

        // DNS Lookup Time Measurement
        String host = "";
        try {
            URI uri = new URI(website.getWebsiteUrl());
            host = uri.getHost();
        } catch (Exception e) {}
        if (host == null || host.isEmpty()) {
            String url = website.getWebsiteUrl();
            if (url.startsWith("http://")) url = url.substring(7);
            else if (url.startsWith("https://")) url = url.substring(8);
            int slashIndex = url.indexOf('/');
            if (slashIndex != -1) url = url.substring(0, slashIndex);
            int colonIndex = url.indexOf(':');
            if (colonIndex != -1) url = url.substring(0, colonIndex);
            host = url;
        }

        double dnsTimeMs = 0.0;
        if (host != null && !host.isEmpty()) {
            try {
                long dnsStart = System.nanoTime();
                java.net.InetAddress.getByName(host);
                dnsTimeMs = (System.nanoTime() - dnsStart) / 1_000_000.0;
                dnsTimeMs = Math.round(dnsTimeMs * 100.0) / 100.0;
            } catch (Exception e) {
                log.warn("DNS lookup failed for {}: {}", host, e.getMessage());
                logCriticalIncident(website, "ERROR", "dns_resolver", 
                        "DNS lookup failed for host: " + host + ". Error: " + e.getMessage(), getStackTraceAsString(e));
            }
        }

        int currentTimeoutMs = settingService.getSettingValueAsInt("global_connection_timeout", timeoutMs);
        int currentMaxRetries = settingService.getSettingValueAsInt("global_retry_count", maxRetries);
        if (website.getThreshold() != null) {
            currentTimeoutMs = website.getThreshold().getConnectionTimeout();
            currentMaxRetries = website.getThreshold().getRetryCount();
        }

        int attempts = 0;
        boolean isUp = false;
        int lastStatusCode = 0;
        long totalDurationMs = 0;

        // Perform probe try + configured retries
        while (attempts <= currentMaxRetries) {
            attempts++;
            long startTime = System.currentTimeMillis();
            
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(website.getWebsiteUrl()))
                        .timeout(Duration.ofMillis(currentTimeoutMs))
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
                            website.getWebsiteUrl(), attempts, currentMaxRetries + 1, lastStatusCode);
                    if (attempts > currentMaxRetries) {
                        logCriticalIncident(website, "CRITICAL", "http_client", 
                                "Probe failed with status code " + lastStatusCode + " after all retries for: " + website.getWebsiteUrl(), null);
                    }
                }
            } catch (Exception e) {
                totalDurationMs = System.currentTimeMillis() - startTime;
                lastStatusCode = 0; // Network exception / Timeout
                log.warn("Probe failed for {} (Attempt {}/{}). Error: {}", 
                        website.getWebsiteUrl(), attempts, currentMaxRetries + 1, e.getMessage());
                if (attempts > currentMaxRetries) {
                    logCriticalIncident(website, "CRITICAL", "http_client", 
                            "Probe failed and exhausted all retries for: " + website.getWebsiteUrl() + ". Error: " + e.getMessage(), 
                            getStackTraceAsString(e));
                }
            }

            // If we have retries left, wait before making the next attempt
            if (attempts <= currentMaxRetries && !isUp) {
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

        // 3. SSL Expiration & Issuer Parsing (if website starts with https)
        Instant sslExpiry = null;
        String sslIssuer = null;
        String protocol = "HTTP";
        if (website.getWebsiteUrl().toLowerCase().startsWith("https://")) {
            protocol = "HTTPS";
            SslDetails ssl = checkSslCertificate(website);
            if (ssl != null) {
                sslExpiry = ssl.expiryDate;
                sslIssuer = ssl.issuer;
            }
        }

        // 4. Update Website state
        website.setStatus(finalStatus);
        website.setResponseTime(responseTime);
        website.setEwmaResponseTime(finalEwma);
        website.setDnsLookupTime(dnsTimeMs);
        website.setProtocol(protocol);
        if (sslExpiry != null) {
            website.setSslExpiryDate(sslExpiry);
        }
        if (sslIssuer != null) {
            website.setSslIssuer(sslIssuer);
        } else if ("HTTP".equals(protocol)) {
            website.setSslIssuer("N/A (HTTP)");
        }
        websiteRepository.save(website);

        // 5. Write to PostgreSQL Time-Series Log Table
        MonitoringLog checkLog = new MonitoringLog(website, lastStatusCode, (int) responseTime, finalStatus);
        logRepository.save(checkLog);

        log.info("Probe completed: {} | Status: {} | Latency: {}ms | EWMA: {}ms | DNS: {}ms | Protocol: {} | SSL Expiry: {} | SSL Issuer: {}",
                website.getWebsiteName(), finalStatus, responseTime, Math.round(finalEwma), dnsTimeMs, protocol, sslExpiry, sslIssuer);
    }

    private static class SslDetails {
        Instant expiryDate;
        String issuer;

        SslDetails(Instant expiryDate, String issuer) {
            this.expiryDate = expiryDate;
            this.issuer = issuer;
        }
    }

    /**
     * Extracts SSL/TLS Certificate Expiration Date and Issuer by executing a secure TCP socket handshake.
     */
    private SslDetails checkSslCertificate(Website website) {
        SSLSocket socket = null;
        String urlString = website.getWebsiteUrl();
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
                Instant expiry = cert.getNotAfter().toInstant();
                String issuerDN = cert.getIssuerX500Principal().getName();
                String issuerName = parseIssuerName(issuerDN);
                return new SslDetails(expiry, issuerName);
            }
        } catch (Exception e) {
            log.warn("Could not extract SSL certificate for {}: {}", urlString, e.getMessage());
            logCriticalIncident(website, "WARNING", "ssl_checker", 
                    "Could not extract SSL certificate for: " + urlString + ". Error: " + e.getMessage(), 
                    getStackTraceAsString(e));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String parseIssuerName(String dn) {
        if (dn == null) return "Unknown";
        int oIndex = dn.indexOf("O=");
        if (oIndex != -1) {
            String sub = dn.substring(oIndex + 2);
            int commaIndex = sub.indexOf(',');
            if (commaIndex != -1) {
                return sub.substring(0, commaIndex).trim().replace("\"", "");
            }
            return sub.trim().replace("\"", "");
        }
        int cnIndex = dn.indexOf("CN=");
        if (cnIndex != -1) {
            String sub = dn.substring(cnIndex + 3);
            int commaIndex = sub.indexOf(',');
            if (commaIndex != -1) {
                return sub.substring(0, commaIndex).trim().replace("\"", "");
            }
            return sub.trim().replace("\"", "");
        }
        return dn;
    }

    private void logCriticalIncident(Website website, String severity, String source, String message, String stackTrace) {
        try {
            CriticalLog criticalLog = new CriticalLog(website, severity, source, message, stackTrace);
            criticalLogRepository.save(criticalLog);
        } catch (Exception e) {
            log.error("Failed to write to critical_logs: {}", e.getMessage(), e);
        }
    }

    private String getStackTraceAsString(Throwable t) {
        if (t == null) return null;
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}
