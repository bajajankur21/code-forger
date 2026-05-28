package com.codeforger.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class GenerationJob {

    private final String id;
    private final String specUrl;
    private final Instant createdAt;

    private volatile JobStatus status;
    private volatile Map<String, String> files;
    private volatile String error;

    public GenerationJob(String specUrl) {
        this.id = UUID.randomUUID().toString();
        this.specUrl = specUrl;
        this.createdAt = Instant.now();
        this.status = JobStatus.QUEUED;
    }

    public String getId() {
        return id;
    }

    public String getSpecUrl() {
        return specUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Map<String, String> getFiles() {
        return files;
    }

    public void setFiles(Map<String, String> files) {
        this.files = files;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
