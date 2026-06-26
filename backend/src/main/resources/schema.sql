-- ====================================================================
-- Web Sites Monitoring System (WSMS) - PostgreSQL Reference DDL
-- ====================================================================

-- 1. Create Websites Configuration & Current State Table
CREATE TABLE IF NOT EXISTS websites (
    id SERIAL PRIMARY KEY,
    website_name VARCHAR(100) NOT NULL,
    website_url VARCHAR(255) NOT NULL UNIQUE,
    check_interval INT DEFAULT 60 NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    response_time DOUBLE PRECISION DEFAULT 0.0,
    ewma_response_time DOUBLE PRECISION DEFAULT 0.0,
    ssl_expiry_date TIMESTAMP WITH TIME ZONE,
    dns_lookup_time DOUBLE PRECISION DEFAULT 0.0,
    ssl_issuer VARCHAR(255),
    protocol VARCHAR(10),
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 1b. Create Website Thresholds Configuration Table
CREATE TABLE IF NOT EXISTS website_thresholds (
    id SERIAL PRIMARY KEY,
    website_id INT UNIQUE NOT NULL,
    ssl_expiry_threshold INT DEFAULT 30 NOT NULL,
    dns_lookup_threshold DOUBLE PRECISION DEFAULT 150.0 NOT NULL,
    ewma_threshold DOUBLE PRECISION DEFAULT 500.0 NOT NULL,
    connection_timeout INT DEFAULT 5000 NOT NULL,
    retry_count INT DEFAULT 3 NOT NULL,
    CONSTRAINT fk_website_thresholds_website FOREIGN KEY (website_id) 
        REFERENCES websites(id) ON DELETE CASCADE
);

-- 2. Create Monitoring Historical Logs (Time-Series Ledger) Table
CREATE TABLE IF NOT EXISTS monitoring_logs (
    id BIGSERIAL PRIMARY KEY,
    website_id INT NOT NULL,
    status_code INT,
    response_time INT, -- in milliseconds
    status VARCHAR(10) NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_monitoring_logs_website FOREIGN KEY (website_id) 
        REFERENCES websites(id) ON DELETE CASCADE
);

-- 3. Create Optimization Indexes for Faster Dashboard Historical Charts
CREATE INDEX IF NOT EXISTS idx_logs_website_checked 
ON monitoring_logs (website_id, checked_at DESC);

-- 4. Create Critical Application & System Logs Table
CREATE TABLE IF NOT EXISTS critical_logs (
    id BIGSERIAL PRIMARY KEY,
    website_id INT,
    severity VARCHAR(20) NOT NULL,
    error_source VARCHAR(50) NOT NULL,
    error_message TEXT NOT NULL,
    stack_trace TEXT,
    resolved BOOLEAN DEFAULT FALSE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    occurred_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_critical_logs_website FOREIGN KEY (website_id) 
        REFERENCES websites(id) ON DELETE SET NULL,
        
    CONSTRAINT chk_critical_logs_severity CHECK (severity IN ('CRITICAL', 'ERROR', 'WARNING')),
    
    CONSTRAINT chk_critical_logs_resolution CHECK (
        (resolved = FALSE AND resolved_at IS NULL) OR 
        (resolved = TRUE AND resolved_at IS NOT NULL)
    )
);

-- 4b. Create Optimization Indexes for Critical Logs
-- Partial index optimized for querying unresolved critical-severity errors quickly
CREATE INDEX IF NOT EXISTS idx_critical_logs_unresolved_critical
ON critical_logs (occurred_at DESC)
WHERE resolved = FALSE AND severity = 'CRITICAL';

-- Index for filtering by website_id + occurred_at
CREATE INDEX IF NOT EXISTS idx_critical_logs_website_occurred
ON critical_logs (website_id, occurred_at DESC);

-- 5. Create System Settings (Global Config & Portal Info) Key-Value Table
CREATE TABLE IF NOT EXISTS system_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    description VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 6. Create Portal Customizations Table
CREATE TABLE IF NOT EXISTS portal_customizations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    header_text VARCHAR(255) NOT NULL,
    footer_text VARCHAR(255) NOT NULL,
    body_content TEXT NOT NULL,
    logo_url TEXT NOT NULL,
    is_active BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);



