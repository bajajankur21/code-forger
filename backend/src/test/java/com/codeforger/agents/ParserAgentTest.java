package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ParserAgentTest {

    private static final String SPEC_URL = "https://example.com/openapi.json";
    private static final String SPEC_BODY = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Pet Store\"}}";

    @Test
    void parse_returnsSchema_whenLlmRespondsCleanly() {
        ApiSchema expected = new ApiSchema(
                "com.petstore",
                List.of(new ApiSchema.Entity("Pet", List.of(
                        new ApiSchema.Field("id", "Long", true),
                        new ApiSchema.Field("name", "String", true)
                ))),
                List.of(new ApiSchema.Endpoint("/pets", "POST", "Pet", ApiSchema.Operation.CREATE))
        );
        String json = """
                {"basePackage":"com.petstore",
                 "entities":[{"name":"Pet","fields":[
                   {"name":"id","type":"Long","required":true},
                   {"name":"name","type":"String","required":true}]}],
                 "endpoints":[{"path":"/pets","method":"POST",
                   "entity":"Pet","operation":"CREATE"}]}
                """;

        ParserAgent agent = newAgent(mockChatClient(() -> responseOf(json)), mockHttpClient());
        ApiSchema result = agent.parse(SPEC_URL);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void parse_retriesTransientLlmFailure_thenSucceeds() {
        String json = "{\"basePackage\":\"com.x\",\"entities\":[],\"endpoints\":[]}";
        AtomicInteger calls = new AtomicInteger();

        ChatClient chatClient = mockChatClient(() -> {
            if (calls.incrementAndGet() == 1) throw new RuntimeException("503 service unavailable");
            return responseOf(json);
        });

        ParserAgent agent = newAgent(chatClient, mockHttpClient());
        assertThat(agent.parse(SPEC_URL).basePackage()).isEqualTo("com.x");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void parse_failsFastOnUnparseableLlmOutput() {
        ChatClient chatClient = mockChatClient(() -> responseOf("this is not json at all"));

        ParserAgent agent = newAgent(chatClient, mockHttpClient());
        assertThatThrownBy(() -> agent.parse(SPEC_URL))
                .isInstanceOf(SpecParseException.class);
    }

    @Test
    void parse_throwsSpecFetchException_whenSpecUrlReturnsServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SPEC_URL)).andRespond(withServerError());

        ParserAgent agent = newAgent(mock(ChatClient.class), builder.build());
        assertThatThrownBy(() -> agent.parse(SPEC_URL))
                .isInstanceOf(SpecFetchException.class);
    }

    // --- helpers -------------------------------------------------------

    private static ParserAgent newAgent(ChatClient chatClient, RestClient httpClient) {
        ByteArrayResource prompt = new ByteArrayResource("Parse this spec: {spec}".getBytes());
        return new ParserAgent(chatClient, httpClient, prompt);
    }

    private static RestClient mockHttpClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SPEC_URL))
                .andRespond(withSuccess(SPEC_BODY, MediaType.APPLICATION_JSON));
        return builder.build();
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
