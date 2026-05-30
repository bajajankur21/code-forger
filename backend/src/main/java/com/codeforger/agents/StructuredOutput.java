package com.codeforger.agents;

import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Extracts structured output from a {@link ChatResponse}.
 *
 * <p>Reasoning models (Gemma 4) return their thinking trace and the final
 * answer as <em>separate</em> {@link Generation}s — the thought first, the
 * answer last. Spring AI's {@code .entity()} reads only the first generation,
 * so it hands back the thinking trace and the parse fails. This walks the
 * generations back-to-front and returns the first one that parses into the
 * target type, which reliably selects the answer over the thought.
 */
final class StructuredOutput {

    private StructuredOutput() {
    }

    static <T> T parse(ChatResponse response, BeanOutputConverter<T> converter) {
        List<Generation> results = response == null ? List.of() : response.getResults();
        RuntimeException lastError = null;
        for (int i = results.size() - 1; i >= 0; i--) {
            var output = results.get(i).getOutput();
            String text = output == null ? null : output.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                T value = converter.convert(text);
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw new IllegalStateException(
                "LLM response contained no parseable structured output", lastError);
    }
}
