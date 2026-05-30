package com.codeforger.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.codeforger.agents.CodeGeneratorAgent;
import com.codeforger.agents.CompileError;
import com.codeforger.agents.ParserAgent;
import com.codeforger.agents.ValidationResult;
import com.codeforger.agents.ValidatorAgent;
import com.codeforger.model.ApiSchema;
import com.codeforger.model.GeneratedCode;
import com.codeforger.model.GenerationJob;
import com.codeforger.model.JobStatus;
import com.codeforger.queue.JobQueue;
import com.codeforger.websocket.StatusBroadcaster;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AgentOrchestratorTest {

    private static final ApiSchema SCHEMA = new ApiSchema(
            "com.petstore",
            List.of(new ApiSchema.Entity("Pet", List.of(
                    new ApiSchema.Field("id", "Long", true)
            ))),
            List.of(new ApiSchema.Endpoint("/pets", "POST", "Pet", ApiSchema.Operation.CREATE))
    );

    @Test
    void processNextJob_completesJob_whenPipelineValidates() {
        JobQueue queue = new JobQueue();
        GenerationJob job = queue.submit("https://example.com/openapi.json");
        GeneratedCode code = new GeneratedCode(Map.of("Pet.java", "class Pet {}"));

        ParserAgent parser = mock(ParserAgent.class);
        CodeGeneratorAgent generator = mock(CodeGeneratorAgent.class);
        ValidatorAgent validator = mock(ValidatorAgent.class);
        StatusBroadcaster broadcaster = mock(StatusBroadcaster.class);

        when(parser.parse(job.getSpecUrl())).thenReturn(SCHEMA);
        when(generator.generate(SCHEMA)).thenReturn(code);
        when(validator.validate(code)).thenReturn(ValidationResult.pass());

        AgentOrchestrator orchestrator =
                new AgentOrchestrator(queue, parser, generator, validator, broadcaster);

        assertThat(orchestrator.processNextJob()).isTrue();

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETE);
        assertThat(job.getFiles()).isEqualTo(code.files());
        assertThat(job.getError()).isNull();

        InOrder order = inOrder(broadcaster);
        order.verify(broadcaster).broadcast(job.getId(), JobStatus.QUEUED, "Job queued");
        order.verify(broadcaster).broadcast(
                job.getId(), JobStatus.PARSING, "Fetching and parsing OpenAPI specification");
        order.verify(broadcaster).broadcast(
                job.getId(), JobStatus.GENERATING, "Generating Spring Boot source files");
        order.verify(broadcaster).broadcast(
                job.getId(), JobStatus.VALIDATING, "Validating generated Java files, pass 1");
        order.verify(broadcaster).broadcast(job.getId(), JobStatus.COMPLETE, "Generation complete");
    }

    @Test
    void processNextJob_correctsCode_whenInitialValidationFails() {
        JobQueue queue = new JobQueue();
        GenerationJob job = queue.submit("https://example.com/openapi.json");
        GeneratedCode broken = new GeneratedCode(Map.of("Pet.java", "class Pet {"));
        GeneratedCode fixed = new GeneratedCode(Map.of("Pet.java", "class Pet {}"));
        List<CompileError> errors = List.of(new CompileError("Pet.java", 1, "'}' expected"));

        ParserAgent parser = mock(ParserAgent.class);
        CodeGeneratorAgent generator = mock(CodeGeneratorAgent.class);
        ValidatorAgent validator = mock(ValidatorAgent.class);
        StatusBroadcaster broadcaster = mock(StatusBroadcaster.class);

        when(parser.parse(job.getSpecUrl())).thenReturn(SCHEMA);
        when(generator.generate(SCHEMA)).thenReturn(broken);
        when(generator.correct(broken, errors)).thenReturn(fixed);
        when(validator.validate(broken)).thenReturn(ValidationResult.fail(errors));
        when(validator.validate(fixed)).thenReturn(ValidationResult.pass());

        AgentOrchestrator orchestrator =
                new AgentOrchestrator(queue, parser, generator, validator, broadcaster);

        assertThat(orchestrator.processNextJob()).isTrue();

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETE);
        assertThat(job.getFiles()).isEqualTo(fixed.files());
        verify(generator).correct(broken, errors);
        verify(broadcaster).broadcast(
                job.getId(), JobStatus.CORRECTING, "Correcting compile errors, attempt 1 of 3");
        verify(broadcaster).broadcast(
                job.getId(), JobStatus.VALIDATING, "Validating generated Java files, pass 2");
    }

    @Test
    void processNextJob_failsJob_whenCorrectionAttemptsAreExhausted() {
        JobQueue queue = new JobQueue();
        GenerationJob job = queue.submit("https://example.com/openapi.json");
        GeneratedCode code = new GeneratedCode(Map.of("Pet.java", "class Pet {"));
        List<CompileError> errors = List.of(new CompileError("Pet.java", 1, "'}' expected"));

        ParserAgent parser = mock(ParserAgent.class);
        CodeGeneratorAgent generator = mock(CodeGeneratorAgent.class);
        ValidatorAgent validator = mock(ValidatorAgent.class);
        StatusBroadcaster broadcaster = mock(StatusBroadcaster.class);

        when(parser.parse(job.getSpecUrl())).thenReturn(SCHEMA);
        when(generator.generate(SCHEMA)).thenReturn(code);
        when(generator.correct(any(GeneratedCode.class), any())).thenReturn(code);
        when(validator.validate(code)).thenReturn(ValidationResult.fail(errors));

        AgentOrchestrator orchestrator =
                new AgentOrchestrator(queue, parser, generator, validator, broadcaster);

        assertThat(orchestrator.processNextJob()).isTrue();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getFiles()).isNull();
        assertThat(job.getError()).contains("Validation failed after 3 correction attempts");
        assertThat(job.getError()).contains("Pet.java:1 '}' expected");
        verify(generator, times(3)).correct(any(GeneratedCode.class), any());
        verify(broadcaster).broadcast(job.getId(), JobStatus.FAILED, job.getError());
    }
}
