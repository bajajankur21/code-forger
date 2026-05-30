package com.codeforger.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        // Gemma 4 is a reasoning model: it always emits a thinking trace plus
        // the answer. maxOutputTokens must be high enough for both so the JSON
        // answer is never truncated; responseMimeType nudges it toward clean
        // JSON. (Thinking cannot be disabled on this model, and that's fine —
        // we want it for generation/correction quality. The model/temperature
        // come from application.yml and are preserved by option merging.)
        return builder
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .maxOutputTokens(32768) // model's max; reasoning trace + multi-file JSON both need room
                        .responseMimeType("application/json"))
                .build();
    }

    @Bean
    RestClient httpClient() {
        return RestClient.create();
    }
}
