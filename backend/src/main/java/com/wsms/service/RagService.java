package com.wsms.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;

    public RagService(JdbcTemplate jdbcTemplate, SimpleVectorStore vectorStore, EmbeddingClient embeddingClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    public String getDatabaseSchema(String query) {
        String websitesSchema = "TABLE websites (\n" +
               "  id SERIAL PRIMARY KEY,\n" +
               "  website_name VARCHAR(100) NOT NULL,\n" +
               "  website_url VARCHAR(255) NOT NULL UNIQUE,\n" +
               "  check_interval INTEGER DEFAULT 60,\n" +
               "  status VARCHAR(20) DEFAULT 'PENDING',\n" +
               "  response_time DOUBLE PRECISION,\n" +
               "  ewma_response_time DOUBLE PRECISION,\n" +
               "  ssl_expiry_date TIMESTAMP,\n" +
               "  dns_lookup_time DOUBLE PRECISION,\n" +
               "  ssl_issuer VARCHAR(255),\n" +
               "  protocol VARCHAR(10),\n" +
               "  ssl_expiry_threshold INTEGER DEFAULT 30\n" +
               ");\n";

        if (query == null || query.trim().isEmpty()) {
            return websitesSchema;
        }

        String lowerQuery = query.toLowerCase();
        boolean needsLogs = lowerQuery.contains("history") || lowerQuery.contains("logs") || 
                            lowerQuery.contains("trend") || lowerQuery.contains("past") || 
                            lowerQuery.contains("change") || lowerQuery.contains("previous") || 
                            lowerQuery.contains("checked") || lowerQuery.contains("record");

        if (needsLogs) {
            return websitesSchema +
               "TABLE monitoring_logs (\n" +
               "  id BIGSERIAL PRIMARY KEY,\n" +
               "  website_id INTEGER REFERENCES websites(id),\n" +
               "  status_code INTEGER,\n" +
               "  response_time INTEGER,\n" +
               "  status VARCHAR(10),\n" +
               "  checked_at TIMESTAMP NOT NULL\n" +
               ");";
        }

        return websitesSchema + "-- Note: Do not query or join any other tables. ONLY query the 'websites' table.";
    }

    public List<Map<String, Object>> executeGeneratedQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }
        
        String cleanSql = sql.trim().toLowerCase();
        
        // Safety check: only allow SELECT queries to prevent database corruption/modification
        if (!cleanSql.startsWith("select")) {
            throw new IllegalArgumentException("For safety, only SELECT queries are permitted.");
        }
        
        // Remove dangerous keywords to prevent multi-statements or privilege escalation
        if (cleanSql.contains("insert") || cleanSql.contains("update") || 
            cleanSql.contains("delete") || cleanSql.contains("drop") || 
            cleanSql.contains("alter") || cleanSql.contains("truncate") || 
            cleanSql.contains("grant") || cleanSql.contains("revoke")) {
            throw new IllegalArgumentException("Query contains unauthorized keywords (INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, etc.).");
        }

        // Limit results to prevent memory exhaustion
        if (!cleanSql.contains("limit")) {
            sql = sql.trim() + " LIMIT 50";
        }

        return jdbcTemplate.queryForList(sql);
    }

    public String searchRunbooks(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }
        
        try {
            List<Double> queryEmbedding = embeddingClient.embed(query);
            List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(5)
            );
            
            System.out.println("DEBUG RAG SEARCH FOR: \"" + query + "\" (total retrieved: " + similarDocuments.size() + "):");
            
            return similarDocuments.stream()
                .filter(doc -> {
                    List<Double> docEmbedding = doc.getEmbedding();
                    double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
                    System.out.println("  - Title: " + doc.getMetadata().get("title") + " | Custom Cosine Similarity: " + similarity);
                    // Filter: only keep documents with similarity >= 0.35
                    return similarity >= 0.35;
                })
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n"));

        } catch (Exception e) {
            System.err.println("Error performing similarity search: " + e.getMessage());
            // Fallback to keyword search if vector search fails
            String sql = "SELECT content FROM security_runbooks WHERE title ILIKE ? OR content ILIKE ? LIMIT 2";
            String wildCard = "%" + query.trim() + "%";
            List<String> results = jdbcTemplate.query(sql, 
                (rs, rowNum) -> rs.getString("content"), 
                wildCard, wildCard);
                
            return String.join("\n\n", results);
        }
    }

    private double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size() || v1.isEmpty()) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            double x = v1.get(i);
            double y = v2.get(i);
            dotProduct += x * y;
            norm1 += x * x;
            norm2 += y * y;
        }
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}

