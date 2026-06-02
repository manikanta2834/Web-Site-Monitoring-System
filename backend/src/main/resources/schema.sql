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
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
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
