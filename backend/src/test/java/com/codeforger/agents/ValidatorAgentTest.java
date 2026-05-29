package com.codeforger.agents;

import com.codeforger.model.GeneratedCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorAgentTest {

    private final ValidatorAgent agent = new ValidatorAgent();

    @Test
    void compilesCleanSource() {
        Map<String, String> files = Map.of(
                "com/example/Hello.java",
                """
                package com.example;
                public class Hello {
                    public String greet() { return "hi"; }
                }
                """
        );

        ValidationResult result = agent.validate(new GeneratedCode(files));

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsErrorsForMissingSemicolon() {
        Map<String, String> files = Map.of(
                "com/example/Broken.java",
                """
                package com.example;
                public class Broken {
                    public String greet() { return "hi" }
                }
                """
        );

        ValidationResult result = agent.validate(new GeneratedCode(files));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0).file()).isEqualTo("com/example/Broken.java");
        assertThat(result.errors().get(0).line()).isGreaterThan(0);
    }

    @Test
    void identifiesWhichFileFailedInMixedBatch() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("com/example/Good.java",
                """
                package com.example;
                public class Good {
                    public int value() { return 1; }
                }
                """);
        files.put("com/example/Bad.java",
                """
                package com.example;
                public class Bad {
                    public int value() { return notAVariable; }
                }
                """);

        ValidationResult result = agent.validate(new GeneratedCode(files));

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .allMatch(e -> e.file().equals("com/example/Bad.java"));
    }
}
