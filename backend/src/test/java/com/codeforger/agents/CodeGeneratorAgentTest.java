package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import com.codeforger.model.GeneratedCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

        CodeGeneratorAgent agent = newAgent(mockChatClientReturning(expected));
        assertThat(agent.generate(SCHEMA)).isEqualTo(expected);
    }

    @Test
    void generate_retriesTransientLlmFailure_thenSucceeds() {
        GeneratedCode out = new GeneratedCode(Map.of("X.java", "class X {}"));
        AtomicInteger calls = new AtomicInteger();

        ChatClient chatClient = mockChatClient(inv -> {
            if (calls.incrementAndGet() == 1) throw new RuntimeException("504 timeout");
            return out;
        });

        CodeGeneratorAgent agent = newAgent(chatClient);
        assertThat(agent.generate(SCHEMA)).isEqualTo(out);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void generate_failsFastOnUnparseableLlmOutput() {
        ChatClient chatClient = mockChatClient(inv -> { throw new RuntimeException("could not parse json"); });

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

        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec reqSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(chatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(prompt.capture())).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(GeneratedCode.class)).thenReturn(fixed);

        CodeGeneratorAgent agent = newAgent(chatClient);
        GeneratedCode result = agent.correct(previous, errors);

        assertThat(result).isEqualTo(fixed);
        assertThat(prompt.getValue()).contains("class Pet { int id }");
        assertThat(prompt.getValue()).contains("Pet.java:1");
        assertThat(prompt.getValue()).contains("';' expected");
        verify(callSpec).entity(GeneratedCode.class);
    }

    // --- helpers -------------------------------------------------------

    private static CodeGeneratorAgent newAgent(ChatClient chatClient) {
        ByteArrayResource gen = new ByteArrayResource("Generate: {schema}".getBytes());
        ByteArrayResource cor = new ByteArrayResource("Correct: {previousCode} ERRORS: {errors}".getBytes());
        return new CodeGeneratorAgent(chatClient, gen, cor);
    }

    private static ChatClient mockChatClientReturning(GeneratedCode out) {
        return mockChatClient(inv -> out);
    }

    private static ChatClient mockChatClient(java.util.function.Function<InvocationOnMock, GeneratedCode> answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClientRequestSpec reqSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(client.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(any(String.class))).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(GeneratedCode.class)).thenAnswer(invocation -> answer.apply(invocation));
        return client;
    }
}
