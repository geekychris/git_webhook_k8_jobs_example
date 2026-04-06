package com.demo.webhook.model;

import java.time.Instant;

public class JobRecord {

    private String jobId;
    private String eventId;
    private String jobName;
    private String repository;
    private int prNumber;
    private String status; // PENDING, RUNNING, SUCCEEDED, FAILED
    private String createdAt;
    private String completedAt;
    private String result;

    public JobRecord() {
        this.createdAt = Instant.now().toString();
        this.status = "PENDING";
    }

    // Getters and setters
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
