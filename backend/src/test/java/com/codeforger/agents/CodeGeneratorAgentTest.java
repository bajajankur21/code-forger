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
        GeneratedCode expected = new GeneratedCode(Map.of(
                "com/petstore/entity/Pet.java", "package com.petstore.entity; public class Pet {}"
        ));
        String json = "{\"files\":{\"com/petstore/entity/Pet.java\":"
                + "\"package com.petstore.entity; public class Pet {}\"}}";

        CodeGeneratorAgent agent = newAgent(mockChatClient(() -> responseOf(json)));
        assertThat(agent.generate(SCHEMA)).isEqualTo(expected);
    }

    @Test
    void generate_retriesTransientLlmFailure_thenSucceeds() {
        GeneratedCode out = new GeneratedCode(Map.of("X.java", "class X {}"));
        String json = "{\"files\":{\"X.java\":\"class X {}\"}}";
        AtomicInteger calls = new AtomicInteger();

        ChatClient chatClient = mockChatClient(() -> {
            if (calls.incrementAndGet() == 1) throw new RuntimeException("504 timeout");
            return responseOf(json);
        });

        CodeGeneratorAgent agent = newAgent(chatClient);
        assertThat(agent.generate(SCHEMA)).isEqualTo(out);
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
    void correct_passesPreviousCodeAndErrorsIntoPrompt() {
        GeneratedCode previous = new GeneratedCode(Map.of(
                "Pet.java", "class Pet { int id }"
        ));
        List<CompileError> errors = List.of(
                new CompileError("Pet.java", 1, "';' expected")
        );
        GeneratedCode fixed = new GeneratedCode(Map.of(
                "Pet.java", "class Pet { int id; }"
        ));
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

        assertThat(result).isEqualTo(fixed);
        assertThat(prompt.getValue()).contains("class Pet { int id }");
        assertThat(prompt.getValue()).contains("Pet.java:1");
        assertThat(prompt.getValue()).contains("';' expected");
        verify(callSpec).chatResponse();
    }

    // --- helpers -------------------------------------------------------

    private static CodeGeneratorAgent newAgent(ChatClient chatClient) {
        ByteArrayResource gen = new ByteArrayResource("Generate: {schema}".getBytes());
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
