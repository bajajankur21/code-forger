package com.codeforger.orchestrator;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "codeforger.orchestrator.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentOrchestrator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_CORRECTION_ATTEMPTS = 3;
    private static final long IDLE_SLEEP_MS = 500;

    private final JobQueue jobQueue;
    private final ParserAgent parserAgent;
    private final CodeGeneratorAgent codeGeneratorAgent;
    private final ValidatorAgent validatorAgent;
    private final StatusBroadcaster statusBroadcaster;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService worker;

    public AgentOrchestrator(
            JobQueue jobQueue,
            ParserAgent parserAgent,
            CodeGeneratorAgent codeGeneratorAgent,
            ValidatorAgent validatorAgent,
            StatusBroadcaster statusBroadcaster) {
        this.jobQueue = jobQueue;
        this.parserAgent = parserAgent;
        this.codeGeneratorAgent = codeGeneratorAgent;
        this.validatorAgent = validatorAgent;
        this.statusBroadcaster = statusBroadcaster;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "code-forger-orchestrator");
            thread.setDaemon(true);
            return thread;
        });
        worker.submit(this::runLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    boolean processNextJob() {
        GenerationJob job = jobQueue.poll();
        if (job == null) {
            return false;
        }
        process(job);
        return true;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                boolean processed = processNextJob();
                if (!processed) {
                    Thread.sleep(IDLE_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            }
        }
    }

    private void process(GenerationJob job) {
        try {
            log.info("Job {} starting for spec {}", job.getId(), job.getSpecUrl());
            update(job, JobStatus.QUEUED, "Job queued");

            update(job, JobStatus.PARSING, "Fetching and parsing OpenAPI specification");
            ApiSchema schema = parserAgent.parse(job.getSpecUrl());
            log.info("Job {} parsed: {} entit(ies), {} endpoint(s)",
                    job.getId(), schema.entities().size(), schema.endpoints().size());

            update(job, JobStatus.GENERATING, "Generating Spring Boot source files");
            GeneratedCode generatedCode = codeGeneratorAgent.generate(schema);
            log.info("Job {} generated {} file(s): {}",
                    job.getId(), generatedCode.files().size(), generatedCode.files().keySet());

            ValidationResult result = validate(job, generatedCode, 1);
            int correctionAttempt = 0;

            while (!result.success() && correctionAttempt < MAX_CORRECTION_ATTEMPTS) {
                correctionAttempt++;
                update(
                        job,
                        JobStatus.CORRECTING,
                        "Correcting compile errors, attempt "
                                + correctionAttempt
                                + " of "
                                + MAX_CORRECTION_ATTEMPTS);
                generatedCode = codeGeneratorAgent.correct(generatedCode, result.errors(), schema);
                result = validate(job, generatedCode, correctionAttempt + 1);
            }

            if (result.success()) {
                job.setFiles(generatedCode.files());
                job.setError(null);
                log.info("Job {} COMPLETE after {} correction attempt(s), {} file(s)",
                        job.getId(), correctionAttempt, generatedCode.files().size());
                update(job, JobStatus.COMPLETE, "Generation complete");
                return;
            }

            fail(job, "Validation failed after "
                    + MAX_CORRECTION_ATTEMPTS
                    + " correction attempts:\n"
                    + renderErrors(result.errors()));
        } catch (RuntimeException e) {
            fail(job, rootMessage(e));
        }
    }

    private ValidationResult validate(GenerationJob job, GeneratedCode generatedCode, int attempt) {
        update(job, JobStatus.VALIDATING, "Validating generated Java files, pass " + attempt);
        ValidationResult result = validatorAgent.validate(generatedCode);
        if (result.success()) {
            log.info("Job {} validation pass {} succeeded", job.getId(), attempt);
        } else {
            log.warn("Job {} validation pass {} found {} compile error(s):\n{}",
                    job.getId(), attempt, result.errors().size(), renderErrors(result.errors()));
        }
        return result;
    }

    private void update(GenerationJob job, JobStatus status, String message) {
        job.setStatus(status);
        statusBroadcaster.broadcast(job.getId(), status, message);
    }

    private void fail(GenerationJob job, String message) {
        job.setError(message);
        log.error("Job {} FAILED: {}", job.getId(), message);
        update(job, JobStatus.FAILED, message);
    }

    private static String renderErrors(List<CompileError> errors) {
        if (errors.isEmpty()) {
            return "Compiler reported failure without diagnostics";
        }
        StringBuilder out = new StringBuilder();
        for (CompileError error : errors) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(error.file())
                    .append(':')
                    .append(error.line())
                    .append(' ')
                    .append(error.message());
        }
        return out.toString();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }
}
