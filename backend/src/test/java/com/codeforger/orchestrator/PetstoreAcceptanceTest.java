package com.codeforger.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeforger.model.GenerationJob;
import com.codeforger.model.JobStatus;
import com.codeforger.queue.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import com.codeforger.agents.ParserAgent;
import com.codeforger.agents.CodeGeneratorAgent;
import com.codeforger.agents.ValidatorAgent;
import com.codeforger.websocket.StatusBroadcaster;

@SpringBootTest
@TestPropertySource(properties = "codeforger.orchestrator.enabled=false")
class PetstoreAcceptanceTest {

    private AgentOrchestrator orchestrator;

    @Autowired
    private JobQueue jobQueue;
    
    @Autowired
    private ParserAgent parserAgent;
    
    @Autowired
    private CodeGeneratorAgent codeGeneratorAgent;
    
    @Autowired
    private ValidatorAgent validatorAgent;
    
    @Autowired
    private StatusBroadcaster statusBroadcaster;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(jobQueue, parserAgent, codeGeneratorAgent, validatorAgent, statusBroadcaster);
    }

    @Test
    void testFullPetstoreGeneration() {
        String petstoreUrl = "https://petstore.swagger.io/v2/swagger.json";
        GenerationJob job = jobQueue.submit(petstoreUrl);

        // Run the orchestrator loop manually for one job
        boolean processed = orchestrator.processNextJob();
        assertThat(processed).isTrue();

        // Verify completion
        if (job.getStatus() == JobStatus.FAILED) {
            System.err.println("Job failed with error: " + job.getError());
        }
        
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETE);
        assertThat(job.getFiles()).isNotEmpty();
        assertThat(job.getError()).isNull();
        
        System.out.println("SUCCESS: Generated " + job.getFiles().size() + " files for Petstore");
    }
}
