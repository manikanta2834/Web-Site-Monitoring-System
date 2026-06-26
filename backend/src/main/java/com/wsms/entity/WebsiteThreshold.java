package com.wsms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "website_thresholds")
public class WebsiteThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "website_id", nullable = false)
    private Website website;

    @Column(name = "ssl_expiry_threshold", nullable = false)
    private Integer sslExpiryThreshold = 30;

    @Column(name = "dns_lookup_threshold", nullable = false)
    private Double dnsLookupThreshold = 150.0;

    @Column(name = "ewma_threshold", nullable = false)
    private Double ewmaThreshold = 500.0;

    @Column(name = "connection_timeout", nullable = false)
    private Integer connectionTimeout = 5000;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 3;

    // Constructors
    public WebsiteThreshold() {}

    public WebsiteThreshold(Website website, Integer sslExpiryThreshold, Double dnsLookupThreshold, Double ewmaThreshold, Integer connectionTimeout, Integer retryCount) {
        this.website = website;
        this.sslExpiryThreshold = sslExpiryThreshold;
        this.dnsLookupThreshold = dnsLookupThreshold;
        this.ewmaThreshold = ewmaThreshold;
        this.connectionTimeout = connectionTimeout;
        this.retryCount = retryCount;
    }

    // Getters and Setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Website getWebsite() {
        return website;
    }

    public void setWebsite(Website website) {
        this.website = website;
    }

    public Integer getSslExpiryThreshold() {
        return sslExpiryThreshold;
    }

    public void setSslExpiryThreshold(Integer sslExpiryThreshold) {
        this.sslExpiryThreshold = sslExpiryThreshold;
    }

    public Double getDnsLookupThreshold() {
        return dnsLookupThreshold;
    }

    public void setDnsLookupThreshold(Double dnsLookupThreshold) {
        this.dnsLookupThreshold = dnsLookupThreshold;
    }

    public Double getEwmaThreshold() {
        return ewmaThreshold;
    }

    public void setEwmaThreshold(Double ewmaThreshold) {
        this.ewmaThreshold = ewmaThreshold;
    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
}
