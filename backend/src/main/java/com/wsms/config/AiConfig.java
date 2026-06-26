package com.wsms.config;

import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${spring.ai.ollama.chat.model:qwen2.5:1.5b}")
    private String modelName;

    @Value("${spring.ai.ollama.embedding.model:all-minilm}")
    private String embeddingModelName;

    @Bean
    public OllamaApi ollamaApi() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(60000); // 60 seconds connection timeout
        requestFactory.setReadTimeout(120000);   // 120 seconds read timeout
        
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        return new OllamaApi(baseUrl, restClientBuilder);
    }

    @Bean
    public OllamaChatClient chatClient(OllamaApi ollamaApi) {
        OllamaOptions options = OllamaOptions.create().withModel(modelName);
        return new OllamaChatClient(ollamaApi).withDefaultOptions(options);
    }

    @Bean
    public OllamaEmbeddingClient embeddingClient(OllamaApi ollamaApi) {
        OllamaOptions options = OllamaOptions.create().withModel(embeddingModelName);
        return new OllamaEmbeddingClient(ollamaApi).withDefaultOptions(options);
    }

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingClient embeddingClient) {
        return new SimpleVectorStore(embeddingClient);
    }
}

