package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import com.codeforger.model.GeneratedCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeGeneratorAgent {

    private final ChatClient chatClient;
    private final RetryTemplate retryTemplate;
    private final String generatePrompt;
    private final String correctPrompt;
    private final BeanOutputConverter<GeneratedCode> outputConverter;

    public CodeGeneratorAgent(
            ChatClient chatClient,
            @Value("classpath:prompts/code-generator.st") Resource generatePromptResource,
            @Value("classpath:prompts/code-generator-correction.st") Resource correctPromptResource
    ) {
        this.chatClient = chatClient;
        this.retryTemplate = buildRetryTemplate();
        this.generatePrompt = readResource(generatePromptResource);
        this.correctPrompt = readResource(correctPromptResource);
        this.outputConverter = new BeanOutputConverter<>(GeneratedCode.class);
    }

    public GeneratedCode generate(ApiSchema schema) {
        String prompt = generatePrompt.replace("{schema}", schema.toString());
        return callLlm(prompt);
    }

    public GeneratedCode correct(GeneratedCode previous, List<CompileError> errors) {
        String prompt = correctPrompt
                .replace("{previousCode}", renderFiles(previous.files()))
                .replace("{errors}", renderErrors(errors));
        return callLlm(prompt);
    }

    private GeneratedCode callLlm(String prompt) {
        // Append the JSON-schema instruction (replacing what .entity() did) and
        // read the full response — a reasoning model returns the answer as a
        // later generation, which StructuredOutput selects over the thought.
        String promptWithFormat = prompt + System.lineSeparator() + outputConverter.getFormat();
        return retryTemplate.execute(ctx -> {
            try {
                ChatResponse response = chatClient.prompt()
                        .user(promptWithFormat)
                        .call()
                        .chatResponse();
                return StructuredOutput.parse(response, outputConverter);
            } catch (RuntimeException e) {
                if (isTransient(e)) throw e;
                throw new CodeGenerationException("LLM returned unusable output", e);
            }
        });
    }

    private static String renderFiles(Map<String, String> files) {
        return files.entrySet().stream()
                .map(e -> "=== " + e.getKey() + " ===\n" + e.getValue())
                .collect(Collectors.joining("\n\n"));
    }

    private static String renderErrors(List<CompileError> errors) {
        return errors.stream()
                .map(e -> e.file() + ":" + e.line() + " — " + e.message())
                .collect(Collectors.joining("\n"));
    }

    private static RetryTemplate buildRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(8000);
        template.setBackOffPolicy(backoff);
        template.setRetryPolicy(new SimpleRetryPolicy(3, Map.of(
                CodeGenerationException.class, false,
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
