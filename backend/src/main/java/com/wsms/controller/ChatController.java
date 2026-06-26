package com.wsms.controller;

import com.wsms.dto.ChatRequestDto;
import com.wsms.dto.ChatResponseDto;
import com.wsms.service.AiService;
import com.wsms.service.RagService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final AiService aiService;
    private final RagService ragService;

    public ChatController(AiService aiService, RagService ragService) {
        this.aiService = aiService;
        this.ragService = ragService;
    }

    @PostMapping("/chat")
    public ChatResponseDto chat(@RequestBody ChatRequestDto request) {
        String message = request.getMessage();
        String generatedSql = "";
        List<Map<String, Object>> queryResults = null;

        // Try to generate SQL query if prompt requests telemetry statistics
        try {
            generatedSql = aiService.generateSql(message);
            if (generatedSql != null && !generatedSql.isEmpty()) {
                queryResults = ragService.executeGeneratedQuery(generatedSql);
            }
        } catch (Exception e) {
            // Log warning but proceed with empty query context to allow chatbot recovery
            generatedSql = "SQL Query compilation failed: " + e.getMessage();
        }

        String aiAnswer = aiService.analyzeTelemetryAndAnswer(message, generatedSql, queryResults);
        return new ChatResponseDto(aiAnswer, generatedSql, queryResults);
    }
}
