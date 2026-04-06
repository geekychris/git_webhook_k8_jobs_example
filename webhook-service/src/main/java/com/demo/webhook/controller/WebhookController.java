package com.demo.webhook.controller;

import com.demo.webhook.client.SidecarClient;
import com.demo.webhook.model.WebhookEvent;
import com.demo.webhook.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final QueueService queueService;
    private final SidecarClient sidecarClient;

    public WebhookController(QueueService queueService, SidecarClient sidecarClient) {
        this.queueService = queueService;
        this.sidecarClient = sidecarClient;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receiveWebhook(@RequestBody WebhookEvent event) {
        log.info("Received webhook: action={} repo={} pr={}",
                event.getAction(), event.getRepository(), event.getPrNumber());

        // Audit the incoming request
        sidecarClient.audit("webhook_received", Map.of(
                "repository", event.getRepository(),
                "pr_number", event.getPrNumber(),
                "action", event.getAction()
        ));

        // Dedup check: TTL-based (same event within 30s window)
        if (queueService.isDuplicate(event)) {
            event.setStatus("DEDUPLICATED");
            queueService.logEvent(event);
            return ResponseEntity.ok(Map.of(
                    "status", "deduplicated",
                    "message", "Duplicate event within debounce window",
                    "event_id", event.getId()
            ));
        }

        // Dedup check: already in queue
        if (queueService.isAlreadyQueued(event)) {
            event.setStatus("DEDUPLICATED");
            queueService.logEvent(event);
            return ResponseEntity.ok(Map.of(
                    "status", "deduplicated",
                    "message", "Identical event already queued",
                    "event_id", event.getId()
            ));
        }

        // Enrich via sidecar
        Map<String, Object> enrichment = sidecarClient.enrich(event);
        event.setEnrichment(enrichment);

        // Queue it
        event.setStatus("QUEUED");
        queueService.enqueue(event);
        queueService.logEvent(event);

        sidecarClient.audit("event_queued", Map.of(
                "event_id", event.getId(),
                "repository", event.getRepository(),
                "pr_number", event.getPrNumber(),
                "enrichment", enrichment
        ));

        return ResponseEntity.accepted().body(Map.of(
                "status", "queued",
                "event_id", event.getId(),
                "enrichment", enrichment
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "queue_size", queueService.queueSize(),
                "active_jobs", queueService.getActiveJobs(),
                "job_history", queueService.getJobHistory(),
                "event_log", queueService.getEventLog()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
