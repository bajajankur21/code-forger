package com.codeforger.agents;

import com.codeforger.model.ApiSchema;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

        ParserAgent agent = newAgent(mockChatClientReturning(expected), mockHttpClient());
        ApiSchema result = agent.parse(SPEC_URL);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void parse_retriesTransientLlmFailure_thenSucceeds() {
        ApiSchema schema = new ApiSchema("com.x", List.of(), List.of());
        AtomicInteger calls = new AtomicInteger();

        ChatClient chatClient = mockChatClient(invocation -> {
            if (calls.incrementAndGet() == 1) throw new RuntimeException("503 service unavailable");
            return schema;
        });

        ParserAgent agent = newAgent(chatClient, mockHttpClient());
        assertThat(agent.parse(SPEC_URL)).isEqualTo(schema);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void parse_failsFastOnUnparseableLlmOutput() {
        ChatClient chatClient = mockChatClient(inv -> { throw new RuntimeException("could not parse json"); });

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

    private static ChatClient mockChatClientReturning(ApiSchema schema) {
        return mockChatClient(inv -> schema);
    }

    private static ChatClient mockChatClient(java.util.function.Function<InvocationOnMock, ApiSchema> answer) {
        ChatClient client = mock(ChatClient.class);
        ChatClientRequestSpec reqSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(client.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(any(String.class))).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.entity(ApiSchema.class)).thenAnswer(invocation -> answer.apply(invocation));
        return client;
    }
}
