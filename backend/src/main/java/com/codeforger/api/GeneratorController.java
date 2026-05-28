package com.codeforger.api;

import com.codeforger.dto.GenerateRequest;
import com.codeforger.dto.JobStatusResponse;
import com.codeforger.model.GenerationJob;
import com.codeforger.model.JobStatus;
import com.codeforger.queue.JobQueue;
import com.codeforger.websocket.StatusBroadcaster;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GeneratorController {

    private final JobQueue jobQueue;
    private final StatusBroadcaster broadcaster;

    public GeneratorController(JobQueue jobQueue, StatusBroadcaster broadcaster) {
        this.jobQueue = jobQueue;
        this.broadcaster = broadcaster;
    }

    @PostMapping("/generate")
    public ResponseEntity<JobStatusResponse> generate(@Valid @RequestBody GenerateRequest request) {
        GenerationJob job = jobQueue.submit(request.specUrl());
        // TODO(step-15): move this broadcast into AgentOrchestrator once it exists.
        broadcaster.broadcast(job.getId(), JobStatus.QUEUED, "Job queued");
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/jobs/" + job.getId()))
                .body(JobStatusResponse.from(job));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        return jobQueue.find(jobId)
                .map(JobStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
