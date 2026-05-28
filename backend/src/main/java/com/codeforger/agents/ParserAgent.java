package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ParserAgent {

    private final ChatClient chatClient;
    private final RestClient httpClient;
    private final RetryTemplate retryTemplate;
    private final String promptTemplate;

    public ParserAgent(
            ChatClient chatClient,
            RestClient httpClient,
            @Value("classpath:prompts/parser.st") Resource promptResource
    ) {
        this.chatClient = chatClient;
        this.httpClient = httpClient;
        this.retryTemplate = buildRetryTemplate();
        this.promptTemplate = readResource(promptResource);
    }

    public ApiSchema parse(String specUrl) {
        String spec = fetchSpec(specUrl);
        String prompt = promptTemplate.replace("{spec}", spec);

        return retryTemplate.execute(ctx -> {
            try {
                return chatClient.prompt()
                        .user(prompt)
                        .call()
                        .entity(ApiSchema.class);
            } catch (RuntimeException e) {
                if (isTransient(e)) throw e;
                throw new SpecParseException("LLM returned unusable output", e);
            }
        });
    }

    private String fetchSpec(String specUrl) {
        try {
            String body = httpClient.get().uri(specUrl).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new SpecFetchException("Empty body from " + specUrl, null);
            }
            return body;
        } catch (RestClientException e) {
            throw new SpecFetchException("Could not fetch spec from " + specUrl, e);
        }
    }

    private static RetryTemplate buildRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(8000);
        template.setBackOffPolicy(backoff);
        template.setRetryPolicy(new SimpleRetryPolicy(3, Map.of(
                SpecParseException.class, false,
                RuntimeException.class, true
        )));
        return template;
    }

    private static boolean isTransient(RuntimeException e) {
        String msg = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase();
        return msg.contains("timeout") || msg.contains("429") || msg.contains("503")
                || msg.contains("502") || msg.contains("504") || msg.contains("unavailable");
    }

    private static String readResource(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read prompt template", e);
        }
    }
}
