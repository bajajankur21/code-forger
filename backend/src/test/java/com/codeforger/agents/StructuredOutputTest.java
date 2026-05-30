package com.codeforger.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeforger.model.ApiSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

class StructuredOutputTest {

    private final BeanOutputConverter<ApiSchema> converter =
            new BeanOutputConverter<>(ApiSchema.class);

    private static final String CLEAN_JSON =
            "{\"basePackage\":\"com.x\",\"entities\":[],\"endpoints\":[]}";

    private static ChatResponse responseOf(String... generationTexts) {
        List<Generation> generations = java.util.Arrays.stream(generationTexts)
                .map(t -> new Generation(new AssistantMessage(t)))
                .toList();
        return new ChatResponse(generations);
    }

    @Test
    void picksAnswerGeneration_overLeadingThoughtTrace() {
        // Mirrors real Gemma output: generation 0 is the markdown thinking
        // trace, generation 1 is the clean JSON answer.
        ChatResponse response = responseOf(
                "*   Reasoning: I will emit JSON.\n*   Valid JSON? Yes.",
                CLEAN_JSON);

        ApiSchema result = StructuredOutput.parse(response, converter);

        assertThat(result.basePackage()).isEqualTo("com.x");
    }

    @Test
    void parsesFencedJson() {
        ChatResponse response = responseOf("```json\n" + CLEAN_JSON + "\n```");

        assertThat(StructuredOutput.parse(response, converter).basePackage())
                .isEqualTo("com.x");
    }

    @Test
    void throwsWhenNoGenerationParses() {
        ChatResponse response = responseOf("just prose, definitely not json");

        assertThatThrownBy(() -> StructuredOutput.parse(response, converter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no parseable structured output");
    }
}
