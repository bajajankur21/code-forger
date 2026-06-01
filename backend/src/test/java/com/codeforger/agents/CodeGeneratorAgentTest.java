package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import com.codeforger.model.GeneratedCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeGeneratorAgentTest {

    private static final ApiSchema SCHEMA = new ApiSchema(
            "com.petstore",
            List.of(new ApiSchema.Entity("Pet", List.of(
                    new ApiSchema.Field("id", "Long", true)
            ))),
            List.of(new ApiSchema.Endpoint("/pets", "POST", "Pet", ApiSchema.Operation.CREATE))
    );

    @Test
    void generate_returnsFiles_whenLlmRespondsCleanly() {
        String json = "{\"files\":{\"com/petstore/entity/Pet.java\":"
                + "\"package com.petstore.entity; public class Pet {}\"}}";

        CodeGeneratorAgent agent = newAgent(mockChatClient(() -> responseOf(json)));
        GeneratedCode result = agent.generate(SCHEMA);

        // Should contain entity files
        assertThat(result.files()).containsKey("com/petstore/entity/Pet.java");
        
        // Should contain shared files
        assertThat(result.files()).containsKey("com/petstore/CodeForgerApplication.java");
        assertThat(result.files()).containsKey("com/petstore/exception/GlobalExceptionHandler.java");
        assertThat(result.files().get("com/petstore/CodeForgerApplication.java")).contains("@SpringBootApplication");
    }

    @Test
    void generate_retriesTransientLlmFailure_thenSucceeds() {
        String json = "{\"files\":{\"X.java\":\"class X {}\"}}";
        AtomicInteger calls = new AtomicInteger();

        ChatClient chatClient = mockChatClient(() -> {
            if (calls.incrementAndGet() == 1) throw new RuntimeException("504 timeout");
            return responseOf(json);
        });

        CodeGeneratorAgent agent = newAgent(chatClient);
        GeneratedCode result = agent.generate(SCHEMA);
        assertThat(result.files()).containsKey("X.java");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void generate_failsFastOnUnparseableLlmOutput() {
        ChatClient chatClient = mockChatClient(() -> responseOf("this is not json at all"));

        CodeGeneratorAgent agent = newAgent(chatClient);
        assertThatThrownBy(() -> agent.generate(SCHEMA))
                .isInstanceOf(CodeGenerationException.class);
    }

    @Test
    void generate_makesMultipleCalls_forMultipleEntities() {
        ApiSchema multiSchema = new ApiSchema(
                "com.test",
                List.of(
                        new ApiSchema.Entity("E1", List.of()),
                        new ApiSchema.Entity("E2", List.of())
                ),
                List.of()
        );

        AtomicInteger calls = new AtomicInteger();
        ChatClient chatClient = mockChatClient(() -> {
            int i = calls.incrementAndGet();
            return responseOf("{\"files\":{\"E" + i + ".java\":\"class E" + i + " {}\"}}");
        });

        CodeGeneratorAgent agent = newAgent(chatClient);
        GeneratedCode result = agent.generate(multiSchema);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.files()).containsKey("E1.java");
        assertThat(result.files()).containsKey("E2.java");
    }

    @Test
    void correct_passesPreviousCodeAndErrorsIntoPrompt() {
        GeneratedCode previous = new GeneratedCode(Map.of(
                "Pet.java", "class Pet { int id }"
        ));
        List<CompileError> errors = List.of(
                new CompileError("Pet.java", 1, "';' expected")
        );
        String json = "{\"files\":{\"Pet.java\":\"class Pet { int id; }\"}}";

        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec reqSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(chatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(prompt.capture())).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(responseOf(json));

        CodeGeneratorAgent agent = newAgent(chatClient);
        GeneratedCode result = agent.correct(previous, errors);

        assertThat(result.files()).containsKey("Pet.java");
        assertThat(prompt.getValue()).contains("class Pet { int id }");
        assertThat(prompt.getValue()).contains("Pet.java:1");
        assertThat(prompt.getValue()).contains("';' expected");
    }

    // --- helpers -------------------------------------------------------

    private static CodeGeneratorAgent newAgent(ChatClient chatClient) {
        // Placeholders matching new prompt format
        ByteArrayResource gen = new ByteArrayResource(
                "Generate: {basePackage} {allEntities} {entitySchema} {endpoints}".getBytes());
        ByteArrayResource cor = new ByteArrayResource("Correct: {previousCode} ERRORS: {errors}".getBytes());
        return new CodeGeneratorAgent(chatClient, gen, cor);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatClient mockChatClient(Supplier<ChatResponse> answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClientRequestSpec reqSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(client.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(any(String.class))).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenAnswer(invocation -> answer.get());
        return client;
    }
}
