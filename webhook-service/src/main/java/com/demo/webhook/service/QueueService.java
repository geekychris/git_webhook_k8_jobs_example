package com.demo.webhook.service;

import com.demo.webhook.model.JobRecord;
import com.demo.webhook.model.WebhookEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final String QUEUE_KEY = "webhook:queue";
    private static final String ACTIVE_JOBS_KEY = "jobs:active";
    private static final String HISTORY_KEY = "jobs:history";
    private static final String EVENTS_KEY = "events:log";
    private static final Duration DEDUP_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public QueueService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public boolean isDuplicate(WebhookEvent event) {
        String key = event.dedupKey();
        Boolean wasAbsent = redis.opsForValue().setIfAbsent(key, "1", DEDUP_TTL);
        if (Boolean.FALSE.equals(wasAbsent)) {
            log.info("Duplicate detected for key: {}", key);
            return true;
        }
        return false;
    }

    public boolean isAlreadyQueued(WebhookEvent event) {
        String queueContent = null;
        List<String> items = redis.opsForList().range(QUEUE_KEY, 0, -1);
        if (items != null) {
            for (String item : items) {
                try {
                    WebhookEvent queued = objectMapper.readValue(item, WebhookEvent.class);
                    if (queued.getRepository().equals(event.getRepository())
                            && queued.getPrNumber() == event.getPrNumber()
                            && queued.getAction().equals(event.getAction())) {
                        log.info("Event already in queue: repo={} pr={} action={}",
                                event.getRepository(), event.getPrNumber(), event.getAction());
                        return true;
                    }
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse queued item", e);
                }
            }
        }
        return false;
    }

    public void enqueue(WebhookEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redis.opsForList().rightPush(QUEUE_KEY, json);
            log.info("Enqueued event: id={} repo={} pr={}", event.getId(), event.getRepository(), event.getPrNumber());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event", e);
        }
    }

    public WebhookEvent dequeue() {
        String json = redis.opsForList().leftPop(QUEUE_KEY);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, WebhookEvent.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize event", e);
            return null;
        }
    }

    public long queueSize() {
        Long size = redis.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    public void trackActiveJob(String jobId, JobRecord record) {
        try {
            redis.opsForHash().put(ACTIVE_JOBS_KEY, jobId, objectMapper.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            log.error("Failed to track job", e);
        }
    }

    public void completeJob(String jobId, String status, String result) {
        String json = (String) redis.opsForHash().get(ACTIVE_JOBS_KEY, jobId);
        if (json != null) {
            try {
                JobRecord record = objectMapper.readValue(json, JobRecord.class);
                record.setStatus(status);
                record.setResult(result);
                record.setCompletedAt(java.time.Instant.now().toString());
                redis.opsForHash().delete(ACTIVE_JOBS_KEY, jobId);
                redis.opsForList().rightPush(HISTORY_KEY, objectMapper.writeValueAsString(record));
                // Keep history trimmed
                redis.opsForList().trim(HISTORY_KEY, -50, -1);
            } catch (JsonProcessingException e) {
                log.error("Failed to complete job", e);
            }
        }
    }

    public int activeJobCount() {
        Long size = redis.opsForHash().size(ACTIVE_JOBS_KEY);
        return size != null ? size.intValue() : 0;
    }

    public List<JobRecord> getActiveJobs() {
        Map<Object, Object> entries = redis.opsForHash().entries(ACTIVE_JOBS_KEY);
        List<JobRecord> jobs = new ArrayList<>();
        for (Object val : entries.values()) {
            try {
                jobs.add(objectMapper.readValue((String) val, JobRecord.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse active job", e);
            }
        }
        return jobs;
    }

    public List<JobRecord> getJobHistory() {
        List<String> items = redis.opsForList().range(HISTORY_KEY, 0, -1);
        List<JobRecord> jobs = new ArrayList<>();
        if (items != null) {
            for (String item : items) {
                try {
                    jobs.add(objectMapper.readValue(item, JobRecord.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse job history", e);
                }
            }
        }
        return jobs;
    }

    public void logEvent(WebhookEvent event) {
        try {
            redis.opsForList().rightPush(EVENTS_KEY, objectMapper.writeValueAsString(event));
            redis.opsForList().trim(EVENTS_KEY, -100, -1);
        } catch (JsonProcessingException e) {
            log.warn("Failed to log event", e);
        }
    }

    public List<WebhookEvent> getEventLog() {
        List<String> items = redis.opsForList().range(EVENTS_KEY, 0, -1);
        List<WebhookEvent> events = new ArrayList<>();
        if (items != null) {
            for (String item : items) {
                try {
                    events.add(objectMapper.readValue(item, WebhookEvent.class));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse event log", e);
                }
            }
        }
        return events;
    }
}
