package com.codeforger.dto;

import com.codeforger.model.GenerationJob;
import com.codeforger.model.JobStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobStatusResponse(
        String jobId,
        JobStatus status,
        Map<String, String> files,
        String error
) {
    public static JobStatusResponse from(GenerationJob job) {
        return new JobStatusResponse(job.getId(), job.getStatus(), job.getFiles(), job.getError());
    }
}
