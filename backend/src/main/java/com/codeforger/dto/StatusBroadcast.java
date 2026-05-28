package com.codeforger.dto;

import com.codeforger.model.JobStatus;
import java.time.Instant;

public record StatusBroadcast(
        String jobId,
        JobStatus phase,
        String message,
        Instant timestamp
) {
    public static StatusBroadcast now(String jobId, JobStatus phase, String message) {
        return new StatusBroadcast(jobId, phase, message, Instant.now());
    }
}
