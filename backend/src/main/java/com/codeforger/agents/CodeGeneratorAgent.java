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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class CodeGeneratorAgent {

    private static final long MIN_INTERVAL_MS = 4000; // 15 RPM -> 4s interval

    private final ChatClient chatClient;
    private final RetryTemplate retryTemplate;
    private final String generatePrompt;
    private final String correctPrompt;
    private final BeanOutputConverter<GeneratedCode> outputConverter;
    private final AtomicLong lastCallTime = new AtomicLong(0);

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
        Map<String, String> allFiles = new HashMap<>();

        // 1. Generate shared deterministic files
        allFiles.putAll(generateSharedFiles(schema.basePackage()));

        // 2. Generate per-entity slices
        String allEntityNames = schema.entities().stream()
                .map(ApiSchema.Entity::name)
                .collect(Collectors.joining(", "));

        for (ApiSchema.Entity entity : schema.entities()) {
            List<ApiSchema.Endpoint> entityEndpoints = schema.endpoints().stream()
                    .filter(e -> e.entity().equalsIgnoreCase(entity.name()))
                    .toList();

            String prompt = generatePrompt
                    .replace("{basePackage}", schema.basePackage())
                    .replace("{allEntities}", allEntityNames)
                    .replace("{entitySchema}", entity.toString())
                    .replace("{endpoints}", entityEndpoints.toString());

            GeneratedCode slice = callLlm(prompt);
            allFiles.putAll(slice.files());
        }

        return new GeneratedCode(allFiles);
    }

    public GeneratedCode correct(GeneratedCode previous, List<CompileError> errors) {
        // For simplicity in v1 chunking, we still correct the whole set if validation fails,
        // but the smaller input (per-entity slices) makes this much more likely to succeed.
        // Future optimization: group errors by entity and only re-generate affected slices.
        String prompt = correctPrompt
                .replace("{previousCode}", renderFiles(previous.files()))
                .replace("{errors}", renderErrors(errors));
        return callLlm(prompt);
    }

    private GeneratedCode callLlm(String prompt) {
        throttle();
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

    private void throttle() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCallTime.get();
        if (elapsed < MIN_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_INTERVAL_MS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Throttler interrupted", e);
            }
        }
        lastCallTime.set(System.currentTimeMillis());
    }

    private Map<String, String> generateSharedFiles(String basePackage) {
        Map<String, String> shared = new HashMap<>();
        String packagePath = basePackage.replace(".", "/");

        // Application.java
        shared.put(packagePath + "/CodeForgerApplication.java",
                "package " + basePackage + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\n" +
                "public class CodeForgerApplication {\n" +
                "    public static void main(String[] args) {\n" +
                "        SpringApplication.run(CodeForgerApplication.class, args);\n" +
                "    }\n" +
                "}\n");

        // ResourceNotFoundException.java
        shared.put(packagePath + "/exception/ResourceNotFoundException.java",
                "package " + basePackage + ".exception;\n\n" +
                "import org.springframework.http.HttpStatus;\n" +
                "import org.springframework.web.bind.annotation.ResponseStatus;\n\n" +
                "@ResponseStatus(HttpStatus.NOT_FOUND)\n" +
                "public class ResourceNotFoundException extends RuntimeException {\n" +
                "    public ResourceNotFoundException(String message) {\n" +
                "        super(message);\n" +
                "    }\n" +
                "}\n");

        // ErrorResponse.java
        shared.put(packagePath + "/exception/ErrorResponse.java",
                "package " + basePackage + ".exception;\n\n" +
                "import lombok.Data;\n" +
                "import java.time.LocalDateTime;\n\n" +
                "@Data\n" +
                "public class ErrorResponse {\n" +
                "    private LocalDateTime timestamp = LocalDateTime.now();\n" +
                "    private int status;\n" +
                "    private String message;\n" +
                "    private String path;\n" +
                "}\n");

        // GlobalExceptionHandler.java
        shared.put(packagePath + "/exception/GlobalExceptionHandler.java",
                "package " + basePackage + ".exception;\n\n" +
                "import org.springframework.http.HttpStatus;\n" +
                "import org.springframework.http.ResponseEntity;\n" +
                "import org.springframework.web.bind.annotation.ExceptionHandler;\n" +
                "import org.springframework.web.bind.annotation.RestControllerAdvice;\n" +
                "import org.springframework.web.context.request.WebRequest;\n\n" +
                "@RestControllerAdvice\n" +
                "public class GlobalExceptionHandler {\n\n" +
                "    @ExceptionHandler(ResourceNotFoundException.class)\n" +
                "    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, WebRequest request) {\n" +
                "        ErrorResponse err = new ErrorResponse();\n" +
                "        err.setStatus(HttpStatus.NOT_FOUND.value());\n" +
                "        err.setMessage(ex.getMessage());\n" +
                "        err.setPath(request.getDescription(false));\n" +
                "        return new ResponseEntity<>(err, HttpStatus.NOT_FOUND);\n" +
                "    }\n" +
                "}\n");

        return shared;
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
