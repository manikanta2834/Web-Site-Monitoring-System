package com.wsms.service;

import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiService {

    private final OllamaChatClient chatClient;
    private final RagService ragService;

    public AiService(OllamaChatClient chatClient, RagService ragService) {
        this.chatClient = chatClient;
        this.ragService = ragService;
    }

    public String generateSql(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "";
        }

        // Only query database if user specifically asks for database facts or lists
        String lowerInput = userInput.toLowerCase();
        if (!lowerInput.contains("show") && !lowerInput.contains("select") && 
            !lowerInput.contains("get") && !lowerInput.contains("website") && 
            !lowerInput.contains("log") && !lowerInput.contains("outage") && 
            !lowerInput.contains("uptime") && !lowerInput.contains("down") && 
            !lowerInput.contains("up") && !lowerInput.contains("latency") && 
            !lowerInput.contains("spike")) {
            return "";
        }

        String promptTemplate = 
            "Translate the natural language request to a single, read-only PostgreSQL SELECT query.\n" +
            "PostgreSQL Schema:\n" +
            "{schema}\n\n" +
            "Request: \"{query}\"\n\n" +
            "Rules:\n" +
            "1. Output format: [SQL] <query> [/SQL]\n" +
            "2. Do NOT write markdown code blocks (```sql or ```) inside or outside the SQL tags.\n" +
            "3. Do NOT include semi-colons inside the SQL tags.\n" +
            "4. Start directly with the [SQL] tag. Do not write any conversational text.\n" +
            "5. Select only specific necessary columns (e.g. website_name, status, response_time) instead of wildcard SELECT *.";

        PromptTemplate template = new PromptTemplate(promptTemplate);
        OllamaOptions options = OllamaOptions.create()
            .withTemperature(0.0f)
            .withNumPredict(80);

        Prompt prompt = new Prompt(template.createMessage(Map.of(
            "schema", ragService.getDatabaseSchema(userInput),
            "query", userInput
        )), options);

        try {
            ChatResponse response = chatClient.call(prompt);
            String text = response.getResult().getOutput().getContent();
            System.out.println("DEBUG RAW LLM SQL OUTPUT: " + text);

            Pattern pattern = Pattern.compile("\\[SQL\\](.*?)(?:\\[/SQL\\]|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);
            String sql = "";
            if (matcher.find()) {
                sql = matcher.group(1).trim();
            } else {
                // Fallback: search for direct select query if tags were omitted by the model
                String lowerText = text.toLowerCase();
                int selectIdx = lowerText.indexOf("select");
                if (selectIdx != -1) {
                    sql = text.substring(selectIdx).trim();
                    int semiColonIdx = sql.indexOf(";");
                    if (semiColonIdx != -1) {
                        sql = sql.substring(0, semiColonIdx).trim();
                    }
                    int newlineIdx = sql.indexOf("\n");
                    if (newlineIdx != -1) {
                        sql = sql.substring(0, newlineIdx).trim();
                    }
                }
            }

            if (sql != null && !sql.isEmpty()) {
                // Clean up any remaining formatting markdown code blocks
                sql = sql.replace("```sql", "").replace("```", "").trim();
                // Ensure query ends without semi-colon for Spring JdbcTemplate handling
                if (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1);
                }
                sql = sql.replace("`", "").trim();
                return sql;
            }
        } catch (Exception e) {
            // Log LLM communication exception and return empty so fallback handles it
            System.err.println("Error calling Ollama to generate SQL: " + e.getMessage());
        }
        return "";
    }

    public String analyzeTelemetryAndAnswer(String userInput, String sql, List<Map<String, Object>> queryResults) {
        String runbooks = ragService.searchRunbooks(userInput);

        String promptTemplate = 
            "You are the WSMS Observability Assistant, a helpful AI assistant built into the Web Sites Monitoring System (WSMS).\n" +
            "User request: \"{query}\"\n" +
            "SQL query executed: {sql}\n" +
            "Data returned:\n{results}\n\n" +
            "Runbook advice:\n{runbooks}\n\n" +
            "Instructions:\n" +
            "1. If the user's request is a general question, greeting, or system access question (such as asking if you can access the dashboard, hello, etc.) that does not need telemetry statistics or runbooks, answer politely and naturally (e.g., explain that as an AI assistant, you run inside the application and the user can access their dashboard directly at http://localhost:8081/ or via the UI). Do NOT claim there is an error, outage, or database issue.\n" +
            "2. If the user is asking about site status, outages, latency, or database details, use the telemetry data and runbook advice above to answer concisely (max 2 sentences). Suggest troubleshooting steps if a website is down.\n" +
            "3. Keep the response concise, helpful, and formatted in clean markdown.";

        PromptTemplate template = new PromptTemplate(promptTemplate);
        OllamaOptions options = OllamaOptions.create()
            .withTemperature(0.1f)
            .withNumPredict(150);

        Prompt prompt = new Prompt(template.createMessage(Map.of(
            "query", userInput,
            "sql", sql == null || sql.isEmpty() ? "None" : sql,
            "results", formatQueryResultsCompact(queryResults),
            "runbooks", runbooks == null || runbooks.isEmpty() ? "No specific runbook match found." : runbooks
        )), options);

        try {
            ChatResponse response = chatClient.call(prompt);
            return response.getResult().getOutput().getContent();
        } catch (Exception e) {
            return "The AI Monitoring Assistant is currently unavailable because the local Ollama service is not running or the 'qwen2.5:1.5b' model is not pulled. Please start Ollama using 'ollama run qwen2.5:1.5b' and try again.\n\nError details: " + e.getMessage();
        }
    }

    private String formatQueryResultsCompact(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "No database records retrieved.";
        }
        
        StringBuilder sb = new StringBuilder();
        // Extract headers from first row
        Map<String, Object> firstRow = results.get(0);
        sb.append(String.join(",", firstRow.keySet())).append("\n");
        
        int limit = Math.min(results.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = results.get(i);
            List<String> values = new java.util.ArrayList<>();
            for (Object val : row.values()) {
                values.add(val == null ? "null" : val.toString());
            }
            sb.append(String.join(",", values)).append("\n");
        }
        
        if (results.size() > 5) {
            sb.append("... and ").append(results.size() - 5).append(" more records exist.\n");
        }
        
        return sb.toString();
    }
}
