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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class CodeGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeGeneratorAgent.class);
    private static final long MIN_INTERVAL_MS = 4000; // 15 RPM -> 4s interval
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

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

        // 1. Generate shared deterministic files (managed by Java, not LLM)
        Map<String, String> shared = generateSharedFiles(schema.basePackage());
        allFiles.putAll(shared);

        // 2. Generate per-entity slices
        String allEntityNames = schema.entities().stream()
                .map(ApiSchema.Entity::name)
                .collect(Collectors.joining(", "));

        for (ApiSchema.Entity entity : schema.entities()) {
            log.info("Generating code slice for entity: {}", entity.name());
            List<ApiSchema.Endpoint> entityEndpoints = schema.endpoints().stream()
                    .filter(e -> e.entity().equalsIgnoreCase(entity.name()))
                    .toList();

            String prompt = generatePrompt
                    .replace("{basePackage}", schema.basePackage())
                    .replace("{allEntities}", allEntityNames)
                    .replace("{entitySchema}", toJson(entity))
                    .replace("{endpoints}", toJson(entityEndpoints));

            GeneratedCode slice = callLlm(prompt);
            
            // Safety: Don't let AI-generated slices overwrite deterministic shared files
            slice.files().forEach((filename, content) -> {
                if (!shared.containsKey(filename)) {
                    allFiles.put(filename, content);
                }
            });
        }

        return new GeneratedCode(allFiles);
    }

    public GeneratedCode correct(GeneratedCode previous, List<CompileError> errors, ApiSchema schema) {
        Map<String, String> currentFiles = new HashMap<>(previous.files());
        
        // Group errors by entity slice to avoid re-sending the entire project
        Map<String, List<CompileError>> errorsByEntity = groupErrorsByEntity(errors, schema);

        for (Map.Entry<String, List<CompileError>> entry : errorsByEntity.entrySet()) {
            String entityName = entry.getKey();
            
            // Skip corrections for shared files (deterministic) or errors that couldn't be mapped
            if ("shared".equals(entityName)) {
                log.info("Skipping LLM correction for shared deterministic files");
                continue;
            }

            log.info("Correcting code slice for entity: {} ({} error(s))", entityName, entry.getValue().size());
            List<CompileError> entityErrors = entry.getValue();

            // Identify all files belonging to this entity slice
            Map<String, String> entitySliceFiles = previous.files().entrySet().stream()
                    .filter(e -> isFilePartOfEntity(e.getKey(), entityName))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            String prompt = correctPrompt
                    .replace("{previousCode}", renderFiles(entitySliceFiles))
                    .replace("{errors}", renderErrors(entityErrors));

            GeneratedCode correctedSlice = callLlm(prompt);
            currentFiles.putAll(correctedSlice.files());
        }

        // Final Safety: Always re-overlay shared files so the LLM can't corrupt or delete them
        currentFiles.putAll(generateSharedFiles(schema.basePackage()));

        return new GeneratedCode(currentFiles);
    }

    private GeneratedCode callLlm(String prompt) {
        String promptWithFormat = prompt + System.lineSeparator() + outputConverter.getFormat();
        return retryTemplate.execute(ctx -> {
            throttle(); // Proactive check INSIDE retry loop to maintain 15 RPM floor
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

    private synchronized void throttle() {
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

    private Map<String, List<CompileError>> groupErrorsByEntity(List<CompileError> errors, ApiSchema schema) {
        Map<String, List<CompileError>> groups = new HashMap<>();
        for (CompileError error : errors) {
            String entityName = detectEntityFromFilename(error.file(), schema);
            groups.computeIfAbsent(entityName, k -> new ArrayList<>()).add(error);
        }
        return groups;
    }

    private String detectEntityFromFilename(String filename, ApiSchema schema) {
        for (ApiSchema.Entity entity : schema.entities()) {
            if (isFilePartOfEntity(filename, entity.name())) {
                return entity.name();
            }
        }
        return "shared"; // Fallback for errors in Application or shared components
    }

    private boolean isFilePartOfEntity(String filename, String entityName) {
        String baseName = filename.substring(filename.lastIndexOf('/') + 1).replace(".java", "");
        return baseName.equals(entityName) || 
               baseName.equals(entityName + "Controller") ||
               baseName.equals(entityName + "Service") ||
               baseName.equals(entityName + "Repository") ||
               baseName.equals(entityName + "DTO");
    }

    private Map<String, String> generateSharedFiles(String basePackage) {
        Map<String, String> shared = new HashMap<>();
        String packagePath = basePackage.replace(".", "/");

        // Application.java (Generic name, no tool branding)
        shared.put(packagePath + "/Application.java",
                "package " + basePackage + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\n" +
                "public class Application {\n" +
                "    public static void main(String[] args) {\n" +
                "        SpringApplication.run(Application.class, args);\n" +
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

    private String toJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
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
