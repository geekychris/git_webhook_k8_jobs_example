package com.demo.webhook.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookEvent {

    private String id;
    private String action;
    private String repository;
    private int prNumber;
    private String prTitle;
    private String sender;
    private String headSha;
    private String baseBranch;
    private String headBranch;
    private Map<String, Object> enrichment;
    private String receivedAt;
    private String status; // QUEUED, PROCESSING, COMPLETED, FAILED, DEDUPLICATED

    public WebhookEvent() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.receivedAt = Instant.now().toString();
        this.status = "RECEIVED";
    }

    public String dedupKey() {
        return String.format("dedup:%s:%d:%s", repository, prNumber, action);
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    @JsonProperty("pr_number")
    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }

    @JsonProperty("pr_title")
    public String getPrTitle() { return prTitle; }
    public void setPrTitle(String prTitle) { this.prTitle = prTitle; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    @JsonProperty("head_sha")
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }

    @JsonProperty("base_branch")
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

    @JsonProperty("head_branch")
    public String getHeadBranch() { return headBranch; }
    public void setHeadBranch(String headBranch) { this.headBranch = headBranch; }

    public Map<String, Object> getEnrichment() { return enrichment; }
    public void setEnrichment(Map<String, Object> enrichment) { this.enrichment = enrichment; }

    public String getReceivedAt() { return receivedAt; }
    public void setReceivedAt(String receivedAt) { this.receivedAt = receivedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
