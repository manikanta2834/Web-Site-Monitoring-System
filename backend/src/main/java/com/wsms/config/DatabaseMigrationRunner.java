package com.wsms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.ai.vectorstore.SimpleVectorStore;

@Component
public class DatabaseMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final SimpleVectorStore vectorStore;

    @Autowired
    public DatabaseMigrationRunner(JdbcTemplate jdbcTemplate, SimpleVectorStore vectorStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
    }


    @Override
    public void run(String... args) throws Exception {
        log.info("Running database migration check to verify websites table columns...");

        String[] alterStatements = {
            "ALTER TABLE websites ADD COLUMN IF NOT EXISTS dns_lookup_time DOUBLE PRECISION DEFAULT 0.0",
            "ALTER TABLE websites ADD COLUMN IF NOT EXISTS ssl_issuer VARCHAR(255)",
            "ALTER TABLE websites ADD COLUMN IF NOT EXISTS protocol VARCHAR(10)",
            "ALTER TABLE websites ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_attempts INT DEFAULT 0 NOT NULL",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS lockout_time TIMESTAMP WITH TIME ZONE"
        };

        for (String sql : alterStatements) {
            try {
                jdbcTemplate.execute(sql);
                log.debug("Executed query successfully: {}", sql);
            } catch (Exception e) {
                log.warn("Query execution failed or skipped (may already exist): {}. Error: {}", sql, e.getMessage());
            }
        }

        // Check if websites table still has the old threshold columns to migrate
        boolean hasOldColumns = false;
        try {
            jdbcTemplate.queryForList("SELECT ssl_expiry_threshold FROM websites LIMIT 1");
            hasOldColumns = true;
        } catch (Exception e) {
            // Column already dropped or doesn't exist
        }

        if (hasOldColumns) {
            log.info("Detected legacy threshold columns in websites table. Executing migration to website_thresholds...");
            try {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS website_thresholds (" +
                        "id SERIAL PRIMARY KEY, " +
                        "website_id INT UNIQUE NOT NULL, " +
                        "ssl_expiry_threshold INT DEFAULT 30 NOT NULL, " +
                        "dns_lookup_threshold DOUBLE PRECISION DEFAULT 150.0 NOT NULL, " +
                        "ewma_threshold DOUBLE PRECISION DEFAULT 500.0 NOT NULL, " +
                        "connection_timeout INT DEFAULT 5000 NOT NULL, " +
                        "retry_count INT DEFAULT 3 NOT NULL, " +
                        "CONSTRAINT fk_website_thresholds_website FOREIGN KEY (website_id) REFERENCES websites(id) ON DELETE CASCADE)");

                jdbcTemplate.execute("INSERT INTO website_thresholds (website_id, ssl_expiry_threshold, dns_lookup_threshold, ewma_threshold, connection_timeout, retry_count) " +
                        "SELECT id, ssl_expiry_threshold, dns_lookup_threshold, ewma_threshold, 5000, 3 FROM websites " +
                        "ON CONFLICT (website_id) DO NOTHING");

                jdbcTemplate.execute("ALTER TABLE websites DROP COLUMN ssl_expiry_threshold");
                jdbcTemplate.execute("ALTER TABLE websites DROP COLUMN dns_lookup_threshold");
                jdbcTemplate.execute("ALTER TABLE websites DROP COLUMN ewma_threshold");
                log.info("Successfully migrated threshold data to website_thresholds and dropped legacy columns.");
            } catch (Exception e) {
                log.error("Failed to migrate threshold columns to website_thresholds: {}", e.getMessage(), e);
            }
        } else {
            // Just ensure website_thresholds exists
            try {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS website_thresholds (" +
                        "id SERIAL PRIMARY KEY, " +
                        "website_id INT UNIQUE NOT NULL, " +
                        "ssl_expiry_threshold INT DEFAULT 30 NOT NULL, " +
                        "dns_lookup_threshold DOUBLE PRECISION DEFAULT 150.0 NOT NULL, " +
                        "ewma_threshold DOUBLE PRECISION DEFAULT 500.0 NOT NULL, " +
                        "connection_timeout INT DEFAULT 5000 NOT NULL, " +
                        "retry_count INT DEFAULT 3 NOT NULL, " +
                        "CONSTRAINT fk_website_thresholds_website FOREIGN KEY (website_id) REFERENCES websites(id) ON DELETE CASCADE)");
            } catch (Exception e) {
                log.error("Failed to ensure website_thresholds table exists: {}", e.getMessage(), e);
            }

            // Clean up legacy threshold records that match the default thresholds exactly
            // so they dynamically inherit global settings instead of overriding them.
            try {
                int rowsDeleted = jdbcTemplate.update("DELETE FROM website_thresholds " +
                        "WHERE ssl_expiry_threshold = 30 " +
                        "AND dns_lookup_threshold = 150.0 " +
                        "AND ewma_threshold = 500.0 " +
                        "AND connection_timeout = 5000 " +
                        "AND retry_count = 3");
                log.info("Cleaned up {} redundant default website thresholds successfully.", rowsDeleted);
            } catch (Exception e) {
                log.error("Failed to clean up redundant website thresholds: {}", e.getMessage(), e);
            }
        }

        log.info("Database migration check completed.");

        log.info("Verifying security and user database schemas...");
        try {
            // 1. Create Roles table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS roles (" +
                    "id SERIAL PRIMARY KEY, " +
                    "name VARCHAR(20) NOT NULL UNIQUE)");

            // 2. Create Users table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id SERIAL PRIMARY KEY, " +
                    "username VARCHAR(50) NOT NULL UNIQUE, " +
                    "email VARCHAR(100) NOT NULL UNIQUE, " +
                    "password VARCHAR(100) NOT NULL, " +
                    "enabled BOOLEAN DEFAULT TRUE NOT NULL, " +
                    "failed_attempts INT DEFAULT 0 NOT NULL, " +
                    "lockout_time TIMESTAMP WITH TIME ZONE, " +
                    "created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");

            // 3. Create User-Roles join table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS user_roles (" +
                    "user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                    "role_id INT NOT NULL REFERENCES roles(id) ON DELETE CASCADE, " +
                    "PRIMARY KEY (user_id, role_id))");

            // 4. Create Refresh Tokens table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS refresh_tokens (" +
                    "id SERIAL PRIMARY KEY, " +
                    "token VARCHAR(255) NOT NULL UNIQUE, " +
                    "user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                    "expiry_date TIMESTAMP WITH TIME ZONE NOT NULL)");

            // 5. Create Security Runbooks table for RAG
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS security_runbooks (" +
                    "id SERIAL PRIMARY KEY, " +
                    "title VARCHAR(150) NOT NULL UNIQUE, " +
                    "category VARCHAR(50) NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");

            log.info("Security database schemas verified successfully.");

            // 6. Create System Settings table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS system_settings (" +
                    "setting_key VARCHAR(100) PRIMARY KEY, " +
                    "setting_value TEXT NOT NULL, " +
                    "description VARCHAR(255), " +
                    "updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");

            // 7. Seed default system settings
            String[][] defaultSettings = {
                {"global_connection_timeout", "5000", "Default connection timeout in milliseconds"},
                {"global_retry_count", "3", "Default retry count for checks"},
                {"global_ssl_expiry_threshold", "30", "Default SSL expiry threshold in days"},
                {"global_dns_lookup_threshold", "150.0", "Default DNS lookup threshold in milliseconds"},
                {"global_ewma_threshold", "500.0", "Default EWMA threshold in milliseconds"},
                {"portal_name", "Web Sites Monitoring System", "Name of the WSMS portal"},
                {"portal_logo_url", "/assets/logo.svg", "URL of the portal logo"},
                {"contact_email", "support@wsms.local", "Contact email for support"}
            };
            for (String[] setting : defaultSettings) {
                jdbcTemplate.update("INSERT INTO system_settings (setting_key, setting_value, description, updated_at) " +
                        "VALUES (?, ?, ?, NOW()) ON CONFLICT (setting_key) DO NOTHING", setting[0], setting[1], setting[2]);
            }
            log.info("System settings schema verified and seeded successfully.");

            // 7b. Create Portal Customizations table
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS portal_customizations (" +
                    "id SERIAL PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL UNIQUE, " +
                    "header_text VARCHAR(255) NOT NULL, " +
                    "footer_text VARCHAR(255) NOT NULL, " +
                    "body_content TEXT NOT NULL, " +
                    "logo_url TEXT NOT NULL, " +
                    "is_active BOOLEAN DEFAULT FALSE NOT NULL, " +
                    "created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
                    "updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");

            // 7c. Seed default portal customization if empty
            Integer customCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM portal_customizations", Integer.class);
            if (customCount == null || customCount == 0) {
                jdbcTemplate.update("INSERT INTO portal_customizations (name, header_text, footer_text, body_content, logo_url, is_active, created_at, updated_at) " +
                        "VALUES ('Default Layout', 'WSMS', 'WSMS Standalone - Advanced Monitoring & Observability Platform', 'Observability Gateway & Observational Control', '/src/assets/logo.svg', true, NOW(), NOW())");
                log.info("Default portal layout customization seeded successfully.");
            }

            // 6. Seed default roles
            jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('ROLE_ADMIN') ON CONFLICT (name) DO NOTHING");
            jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('ROLE_OPERATOR') ON CONFLICT (name) DO NOTHING");
            jdbcTemplate.execute("INSERT INTO roles (name) VALUES ('ROLE_VIEWER') ON CONFLICT (name) DO NOTHING");

            // 7. Seed default RAG runbooks
            jdbcTemplate.update("INSERT INTO security_runbooks (title, category, content) VALUES " +
                    "('SSL Expiry Playbook', 'SSL', 'If an SSL certificate is close to expiration (under 14 days), immediately identify the issuer (e.g. Let''s Encrypt, DigiCert) and renew using certbot or your domain controller. Update JVM truststores if a custom internal CA is used.') " +
                    "ON CONFLICT (title) DO NOTHING");

            jdbcTemplate.update("INSERT INTO security_runbooks (title, category, content) VALUES " +
                    "('Outage Recovery', 'Outage', 'For HTTP 500/502/503 errors, check the target application server status, verify database connectivity, inspect Tomcat/Spring Boot console log streams, and check system load. If it is a connection timeout, verify security group ingress rules and firewall limits.') " +
                    "ON CONFLICT (title) DO NOTHING");

            jdbcTemplate.update("INSERT INTO security_runbooks (title, category, content) VALUES " +
                    "('Response Time Spikes', 'Latency', 'When EWMA response time exceeds the threshold, check for database connection pool exhaustion, memory leak spikes (check GC pause metrics), network throttling, or high CPU utilization on host virtual machines.') " +
                    "ON CONFLICT (title) DO NOTHING");

            // 6. Seed default administrator user
            String hashedPassword = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Admin@123");
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'admin'", Integer.class);
            if (count == null || count == 0) {
                jdbcTemplate.update("INSERT INTO users (username, email, password, enabled, failed_attempts, created_at) VALUES ('admin', 'admin@wsms.local', ?, true, 0, NOW())", hashedPassword);
            } else {
                // Force update the password in case it was altered or old, and reset failed attempts/lockout on startup
                jdbcTemplate.update("UPDATE users SET password = ?, enabled = true, failed_attempts = 0, lockout_time = NULL WHERE username = 'admin'", hashedPassword);
            }
            
            Integer userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = 'admin'", Integer.class);
            Integer roleId = jdbcTemplate.queryForObject("SELECT id FROM roles WHERE name = 'ROLE_ADMIN'", Integer.class);
            
            if (userId != null && roleId != null) {
                jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, roleId);
            }
            log.info("Default administrator account 'admin' created or updated successfully with password 'Admin@123'.");

            // Print existing users for verification
            jdbcTemplate.query("SELECT username, email FROM users", (rs, rowNum) -> {
                log.info("User in Database: {} (email: {})", rs.getString("username"), rs.getString("email"));
                return null;
            });

            log.info("Initializing SimpleVectorStore with security runbooks...");
            java.util.List<org.springframework.ai.document.Document> documents = jdbcTemplate.query(
                "SELECT title, category, content FROM security_runbooks",
                (rs, rowNum) -> {
                    java.util.Map<String, Object> metadata = new java.util.HashMap<>();
                    metadata.put("title", rs.getString("title"));
                    metadata.put("category", rs.getString("category"));
                    return new org.springframework.ai.document.Document(rs.getString("content"), metadata);
                }
            );
            vectorStore.add(documents);
            log.info("Successfully loaded {} runbook documents into vector store.", documents.size());

        } catch (Exception e) {
            log.error("Failed to seed security database schema. Error: {}", e.getMessage(), e);
        }
    }
}
